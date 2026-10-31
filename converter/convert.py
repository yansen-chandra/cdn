"""Reference converter engine for the master list.

This is the spec and test oracle for the app's engine. It uses exact rational
math (fractions.Fraction) so results never drift; port it to Kotlin with a
rational type or BigDecimal with a fixed MathContext.

Pipeline:
    text -> fast_parse() -> (LLM fallback, see parse-prompt.md) -> process()
            process() = resolve terms -> convert -> format, with notes

Usage:
    python convert.py "5'9 to cm"            # needs the LLM for compound input
    python convert.py "10 l to gallons" --region GB
    python convert.py "50 sgd to jpy" --rates rates.json
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from decimal import Decimal, localcontext
from fractions import Fraction
from pathlib import Path

HERE = Path(__file__).resolve().parent


class ConversionError(ValueError):
    """Raised for anything the user should be told about (bad input, wrong units)."""


# --------------------------------------------------------------------------
# Numbers
# --------------------------------------------------------------------------

def exact(s: str) -> Fraction:
    """'0.3048' or '1852/3600' -> exact Fraction."""
    if "/" in s:
        num, den = s.split("/")
        return Fraction(num) / Fraction(den)
    return Fraction(s)


_SUFFIX = {"k": 10**3, "m": 10**6, "b": 10**9}
_VALUE_RE = re.compile(
    r"^(?P<sign>-)?"
    r"(?:(?P<whole>\d+)\s+)?"             # "1 " in "1 1/2"
    r"(?P<num>\d+(?:\.\d+)?)"
    r"(?:/(?P<den>\d+(?:\.\d+)?))?"
    r"(?P<suffix>[kmb])?$",
    re.IGNORECASE,
)


def parse_value(text: str) -> Fraction:
    """Parse '2.5', '1,000', '1/2', '1 1/2', '-40', '2.5k'.

    Commas are treated as thousands separators. If you support locales that
    use a decimal comma ('2,5'), normalise before calling this.
    """
    s = text.strip().replace(",", "").replace("_", "")
    m = _VALUE_RE.match(s)
    if not m:
        raise ConversionError(f"Not a number: {text!r}")
    value = Fraction(m["num"])
    if m["den"]:
        den = Fraction(m["den"])
        if den == 0:
            raise ConversionError("Division by zero in fraction")
        value /= den
    if m["whole"]:
        if not m["den"]:
            raise ConversionError(f"Not a number: {text!r}")
        value += int(m["whole"])
    if m["suffix"]:
        value *= _SUFFIX[m["suffix"].lower()]
    return -value if m["sign"] else value


def fmt(x: Fraction, sig: int = 6) -> str:
    """Round to `sig` significant digits, no exponent for everyday magnitudes."""
    if x == 0:
        return "0"
    with localcontext() as ctx:
        ctx.prec = sig
        d = +(Decimal(x.numerator) / Decimal(x.denominator))
    s = format(d, "f")
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s


# --------------------------------------------------------------------------
# Master list
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class Unit:
    id: str
    quantity: str
    kind: str
    factor: Fraction
    offset: Fraction
    symbol: str
    name_one: str
    name_other: str
    regions: tuple[str, ...]
    approximate: bool
    system: str

    def to_base(self, v: Fraction) -> Fraction:
        if self.kind == "linear":
            return v * self.factor
        if self.kind == "affine":
            return v * self.factor + self.offset
        if v == 0:
            raise ConversionError(f"0 {self.symbol} has no equivalent")
        return self.factor / v  # reciprocal

    def from_base(self, b: Fraction) -> Fraction:
        if self.kind == "linear":
            return b / self.factor
        if self.kind == "affine":
            return (b - self.offset) / self.factor
        if b == 0:
            raise ConversionError(f"Result has no equivalent in {self.symbol}")
        return self.factor / b  # reciprocal


@dataclass(frozen=True)
class Resolved:
    id: str                 # unit id or ISO currency code
    domain: str             # "unit" | "currency"
    note: str | None = None # shown to the user when an assumption was made


class MasterList:
    def __init__(self, data: dict):
        self.data = data
        self.version = data["version"]
        self.quantities = {q["id"]: q for q in data["quantities"]}
        self.units: dict[str, Unit] = {}
        for u in data["units"]:
            en = u["names"].get("en", {"one": u["id"], "other": u["id"]})
            self.units[u["id"]] = Unit(
                id=u["id"],
                quantity=u["quantity"],
                kind=u["kind"],
                factor=exact(u["factor"]),
                offset=exact(u.get("offset", "0")),
                symbol=u["symbol"],
                name_one=en["one"],
                name_other=en["other"],
                regions=tuple(u.get("regions", [])),
                approximate=u.get("approximate", False),
                system=u["system"],
            )

        # Unit ids count as aliases too, so the LLM can return either.
        self.unit_alias: dict[str, str] = {}
        for u in data["units"]:
            for a in [u["id"], *u["aliases"]]:
                self.unit_alias.setdefault(a.strip().lower(), u["id"])

        self.currency_codes = {c["code"] for c in data["currency"]["aliases"]}
        self.currency_alias: dict[str, str] = {}
        for c in data["currency"]["aliases"]:
            for a in [c["code"], *c["aliases"]]:
                self.currency_alias.setdefault(a.strip().lower(), c["code"])

        self.ambiguous = {
            (a["domain"], a["alias"].strip().lower()): a for a in data["ambiguous_aliases"]
        }

        self.densities = {d["id"]: d for d in data["densities"]}
        self.density_alias: dict[str, str] = {}
        for d in data["densities"]:
            for a in [d["id"], *d["aliases"]]:
                self.density_alias.setdefault(a.strip().lower(), d["id"])

    @classmethod
    def load(cls, path: str | Path = HERE / "master-list.sample.json") -> "MasterList":
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    # ---------------- validation (run in CI on every master-list change) ---

    def validate(self) -> list[str]:
        errors: list[str] = []
        d = self.data

        def dupes(items):
            seen, out = set(), set()
            for i in items:
                (out if i in seen else seen).add(i)
            return sorted(out)

        for i in dupes([u["id"] for u in d["units"]]):
            errors.append(f"duplicate unit id: {i}")
        for i in dupes([q["id"] for q in d["quantities"]]):
            errors.append(f"duplicate quantity id: {i}")

        for q in d["quantities"]:
            base = self.units.get(q["base_unit"])
            if base is None:
                errors.append(f"quantity {q['id']}: base unit {q['base_unit']} missing")
            elif base.quantity != q["id"] or base.kind != "linear" or base.factor != 1:
                errors.append(f"quantity {q['id']}: base unit must be linear, factor 1, same quantity")
            for t in q.get("common_targets", []):
                if t not in self.units or self.units[t].quantity != q["id"]:
                    errors.append(f"quantity {q['id']}: bad common target {t}")

        for u in d["units"]:
            if u["quantity"] not in self.quantities:
                errors.append(f"unit {u['id']}: unknown quantity {u['quantity']}")
            if exact(u["factor"]) == 0:
                errors.append(f"unit {u['id']}: factor is zero")

        # Every alias must point to exactly one thing within its domain.
        unit_terms: dict[str, set[str]] = {}
        for u in d["units"]:
            for a in [u["id"], *u["aliases"]]:
                unit_terms.setdefault(a.strip().lower(), set()).add(u["id"])
        for a, ids in sorted(unit_terms.items()):
            if len(ids) > 1:
                errors.append(f"unit alias {a!r} maps to {sorted(ids)}; move it to ambiguous_aliases")

        cur_terms: dict[str, set[str]] = {}
        for c in d["currency"]["aliases"]:
            for a in [c["code"], *c["aliases"]]:
                cur_terms.setdefault(a.strip().lower(), set()).add(c["code"])
        for a, codes in sorted(cur_terms.items()):
            if len(codes) > 1:
                errors.append(f"currency alias {a!r} maps to {sorted(codes)}")

        dens_terms: dict[str, set[str]] = {}
        for den in d["densities"]:
            for a in [den["id"], *den["aliases"]]:
                dens_terms.setdefault(a.strip().lower(), set()).add(den["id"])
        for a, ids in sorted(dens_terms.items()):
            if len(ids) > 1:
                errors.append(f"ingredient alias {a!r} maps to {sorted(ids)}")

        for (domain, alias), amb in self.ambiguous.items():
            plain = unit_terms if domain == "unit" else cur_terms
            if alias in plain:
                errors.append(f"ambiguous alias {alias!r} also listed as a plain {domain} alias")
            known = self.units if domain == "unit" else self.currency_codes
            for c in amb["candidates"]:
                if c not in known:
                    errors.append(f"ambiguous alias {alias!r}: unknown candidate {c}")
            for region, choice in amb["defaults"].items():
                if choice not in amb["candidates"]:
                    errors.append(f"ambiguous alias {alias!r}: default {region}->{choice} not a candidate")

        return errors

    # ---------------- resolution ----------------------------------------

    def resolve(self, term: str, region: str = "*", domain: str | None = None,
                prefer_quantity: str | None = None) -> Resolved | None:
        """Map a user/LLM term to a unit id or currency code.

        Order: exact alias -> ambiguous alias with region default. When the
        other side of the conversion is known (prefer_quantity), ambiguous
        candidates of that quantity win ('oz' -> fluid ounce when the target
        is a volume).
        """
        t = term.strip().lower()
        if not t:
            return None
        domains = [domain] if domain else ["unit", "currency"]
        for dom in domains:
            if dom == "unit" and t in self.unit_alias:
                return Resolved(self.unit_alias[t], "unit")
            if dom == "currency" and t in self.currency_alias:
                return Resolved(self.currency_alias[t], "currency")
            amb = self.ambiguous.get((dom, t))
            if amb:
                choice = amb["defaults"].get(region, amb["defaults"]["*"])
                if dom == "unit" and prefer_quantity and self.units[choice].quantity != prefer_quantity:
                    same_q = [c for c in amb["candidates"] if self.units[c].quantity == prefer_quantity]
                    if same_q:
                        regional = [c for c in same_q if region in self.units[c].regions]
                        choice = (regional or same_q)[0]
                label = self.units[choice].name_one if dom == "unit" else choice
                note = amb.get("note", "Assumed {unit}").replace("{unit}", label)
                return Resolved(choice, dom, note)
        return None

    def resolve_ingredient(self, term: str | None) -> str | None:
        if not term:
            return None
        return self.density_alias.get(term.strip().lower())

    # ---------------- conversion -----------------------------------------

    def convert(self, parts: list[tuple[Fraction, str]], to_id: str,
                ingredient: str | None = None) -> Fraction:
        """Convert one or more (value, unit_id) parts to `to_id`."""
        if not parts:
            raise ConversionError("Nothing to convert")
        src = [self.units[u] for _, u in parts]
        dst = self.units[to_id]
        q = src[0].quantity
        if any(u.quantity != q for u in src):
            raise ConversionError("Mixed quantities in one amount")
        if len(parts) > 1 and any(u.kind != "linear" for u in src):
            raise ConversionError("Only plain units can be added together")

        base = sum((u.to_base(v) for (v, _), u in zip(parts, src)), Fraction(0))

        if dst.quantity == q:
            return dst.from_base(base)

        # volume <-> mass via ingredient density. Bases: liter and kilogram.
        # kg = L * 1000 mL/L * g/mL / 1000 g/kg = L * g_per_ml
        pair = {q, dst.quantity}
        if pair == {"volume", "mass"}:
            if not ingredient:
                raise ConversionError("Converting volume to weight needs an ingredient (e.g. rice, flour)")
            g_per_ml = exact(self.densities[ingredient]["g_per_ml"])
            base = base * g_per_ml if q == "volume" else base / g_per_ml
            return dst.from_base(base)

        raise ConversionError(f"Can't convert {self.quantities[q]['names']['en'].lower()} "
                              f"to {self.quantities[dst.quantity]['names']['en'].lower()}")


def convert_currency(amount: Fraction, src: str, dst: str, rates: dict) -> Fraction:
    """rates = {"base": "EUR", "date": "...", "rates": {"SGD": "1.5", ...}}"""
    table = {rates["base"]: Fraction(1), **{k: exact(str(v)) for k, v in rates["rates"].items()}}
    for code in (src, dst):
        if code not in table:
            raise ConversionError(f"No rate for {code}")
    return amount / table[src] * table[dst]


# --------------------------------------------------------------------------
# Fast parser: handles clean input without calling the LLM.
# Returns the same contract as the LLM (see parse-prompt.md) or None.
# --------------------------------------------------------------------------

# No k/m/b suffix here: "5km" and "3m" must stay units. The LLM handles "2.5k".
_NUM = r"-?\d[\d,]*(?:\.\d+)?(?:\s+\d+/\d+|/\d+)?"
_SYMBOL = r"(?:s\$|us\$|a\$|hk\$|[$€£¥₹฿₩₱])"
_SEP = r"\s+(?:to|in|into|as)\s+|\s*(?:->|=|→)\s*"
_FAST_RE = re.compile(
    rf"^\s*(?P<sym>{_SYMBOL})?\s*(?P<num>{_NUM})\s*(?P<unit>[^\d\s=][^\d=]*?)?"
    rf"(?:(?:{_SEP})(?P<to>.+?))?\s*\??\s*$",
    re.IGNORECASE,
)


_SYMBOL_RE = re.compile(
    rf"^\s*(?P<sym>{_SYMBOL})\s*(?P<num>{_NUM})(?:(?:{_SEP})(?P<to>.+?))?\s*\??\s*$",
    re.IGNORECASE,
)


def fast_parse(text: str) -> dict | None:
    m = _SYMBOL_RE.match(text) or _FAST_RE.match(text)
    if not m:
        return None
    m = {**{"sym": None, "unit": None, "to": None}, **m.groupdict()}
    if m["sym"] and m["unit"]:
        return None  # "$50 sgd to jpy" is unusual; let the LLM handle it
    unit = (m["sym"] or m["unit"] or "").strip().lower()
    if not unit:
        return None
    to = [t for t in re.split(r"\s*(?:,|\band\b|&)\s*", m["to"] or "") if t]
    value = m["num"].strip()
    return {
        "intent": "convert",
        "domain": None,
        "parts": [{"value": value, "unit": unit}],
        "to": [t.strip().lower() for t in to],
        "ingredient": None,
    }


def fast_path(text: str, ml: MasterList, region: str = "*") -> dict | None:
    """Use the fast parser only when every term resolves; otherwise return None
    so the caller sends the text to the LLM ('2 cups rice in grams')."""
    req = fast_parse(text)
    if req is None:
        return None
    terms = [p["unit"] for p in req["parts"]] + req["to"]
    if all(ml.resolve(t, region) for t in terms):
        return req
    return None


# --------------------------------------------------------------------------
# End to end: request (fast_parse or LLM output) -> results for display
# --------------------------------------------------------------------------

def process(request: dict, ml: MasterList, region: str = "*",
            rates: dict | None = None, sig: int = 6) -> dict:
    """Returns {"results": [...], "notes": [...], "unresolved": [...], "error": str|None}."""
    out = {"results": [], "notes": [], "unresolved": [], "error": None}
    if request.get("intent") != "convert":
        out["error"] = "incomplete" if request.get("intent") == "incomplete" else "unsupported"
        return out
    domain = request.get("domain")

    try:
        parts = [(parse_value(p["value"]), p["unit"]) for p in request["parts"]]
    except ConversionError as e:
        out["error"] = str(e)
        return out

    # Resolve targets first: their quantity helps pick ambiguous source units.
    targets = []
    for t in request.get("to", []):
        r = ml.resolve(t, region, domain)
        if r is None:
            out["unresolved"].append(t)
        else:
            targets.append(r)
    target_q = next((ml.units[r.id].quantity for r in targets if r.domain == "unit"), None)

    sources = []
    for v, term in parts:
        r = ml.resolve(term, region, domain, prefer_quantity=target_q)
        if r is None:
            out["unresolved"].append(term)
        else:
            sources.append((v, r))
    if out["unresolved"]:
        return out

    src_domain = sources[0][1].domain
    if any(r.domain != src_domain for _, r in sources):
        out["error"] = "Mixed units and currencies"
        return out
    for _, r in sources:
        if r.note:
            out["notes"].append(r.note)

    if not targets:  # no target given: use the quantity's common targets
        if src_domain == "unit":
            q = ml.units[sources[0][1].id].quantity
            targets = [Resolved(t, "unit") for t in ml.quantities[q].get("common_targets", [])
                       if t not in {r.id for _, r in sources}]
        else:
            out["error"] = "Pick a target currency"
            return out

    for r in targets:
        if r.note:
            out["notes"].append(r.note)

    ingredient = ml.resolve_ingredient(request.get("ingredient"))
    if request.get("ingredient") and not ingredient:
        out["unresolved"].append(request["ingredient"])
        return out

    try:
        for r in targets:
            if src_domain == "currency":
                if r.domain != "currency":
                    raise ConversionError("Can't convert currency to a unit")
                if rates is None:
                    raise ConversionError("No exchange rates loaded")
                amount = sum((v for v, _ in sources), Fraction(0))
                value = convert_currency(amount, sources[0][1].id, r.id, rates)
                out["results"].append({"value": fmt(value, sig), "unit": r.id, "exact": value})
            else:
                if r.domain != "unit":
                    raise ConversionError("Can't convert a unit to a currency")
                value = ml.convert([(v, s.id) for v, s in sources], r.id, ingredient)
                u = ml.units[r.id]
                out["results"].append({"value": fmt(value, sig), "unit": r.id, "symbol": u.symbol,
                                       "exact": value})
    except ConversionError as e:
        out["error"] = str(e)
        return out

    if src_domain == "currency":
        out["notes"].append(ml.data["currency"]["provider"]["attribution"].replace("{date}", rates.get("date", "?")))
    if ingredient:
        out["notes"].append(f"Approximate: based on {ml.densities[ingredient]['names']['en'].lower()} density")
    used = [r.id for r in targets] + [s.id for _, s in sources]
    if src_domain == "unit" and any(ml.units[u].system in ("regional", "historical")
                                    and "SG" in ml.units[u].regions for u in used):
        out["notes"].append("Reference only: only metric units are permitted for trade in Singapore")
    # de-duplicate notes, keep order
    out["notes"] = list(dict.fromkeys(out["notes"]))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("text")
    ap.add_argument("--region", default="*")
    ap.add_argument("--rates", help="rates JSON: {base, date, rates:{CODE: rate}}")
    ap.add_argument("--master", default=str(HERE / "master-list.sample.json"))
    args = ap.parse_args()

    ml = MasterList.load(args.master)
    req = fast_parse(args.text)
    if req is None:
        print("fast parser: no match -> send to LLM (parse-prompt.md)")
        return
    rates = json.loads(Path(args.rates).read_text()) if args.rates else None
    res = process(req, ml, args.region, rates)
    res["results"] = [{k: v for k, v in r.items() if k != "exact"} for r in res["results"]]
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
