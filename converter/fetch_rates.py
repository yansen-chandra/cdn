"""Daily rates job: pull reference rates from Frankfurter and write rates.json.

Phones never call the provider; they download the rates.json this job publishes.
The output matches what convert.py expects: {"base", "date", "rates": {CODE: "rate"}}.
Rates are written as plain decimal strings so the engine's exact() can read them.

If the fetch or any check fails, the existing rates.json is left untouched
(last good file wins) and the script exits non-zero.

Run:
    python fetch_rates.py                      # writes ./rates.json
    python fetch_rates.py --out path/rates.json --url https://...
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import urllib.request
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_URL = "https://api.frankfurter.dev/v1/latest?base=EUR"
MIN_CURRENCIES = 20
MAX_AGE_DAYS = 7  # ECB skips weekends and holidays, so allow a few days


class RatesError(Exception):
    pass


def fetch(url: str, timeout: int = 30) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "pixeltools-rates-job/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def normalize(payload: dict, today: date | None = None) -> dict:
    """Check the provider response and turn it into the app's rates.json shape."""
    today = today or date.today()
    try:
        base, day, raw = payload["base"], payload["date"], payload["rates"]
    except (KeyError, TypeError):
        raise RatesError("Response is missing base, date or rates")

    if not (isinstance(base, str) and len(base) == 3 and base.isupper()):
        raise RatesError(f"Bad base currency: {base!r}")
    try:
        when = date.fromisoformat(day)
    except (TypeError, ValueError):
        raise RatesError(f"Bad date: {day!r}")
    if when > today + timedelta(days=1):
        raise RatesError(f"Rate date {day} is in the future")
    if when < today - timedelta(days=MAX_AGE_DAYS):
        raise RatesError(f"Rate date {day} is older than {MAX_AGE_DAYS} days")

    rates = {}
    for code, value in raw.items():
        if not (isinstance(code, str) and len(code) == 3 and code.isupper()):
            raise RatesError(f"Bad currency code: {code!r}")
        try:
            d = Decimal(str(value))
        except InvalidOperation:
            raise RatesError(f"Bad rate for {code}: {value!r}")
        if not d.is_finite() or d <= 0:
            raise RatesError(f"Bad rate for {code}: {value!r}")
        rates[code] = format(d.normalize(), "f")
    rates.pop(base, None)
    if len(rates) < MIN_CURRENCIES:
        raise RatesError(f"Only {len(rates)} rates, expected at least {MIN_CURRENCIES}")

    return {"base": base, "date": day, "rates": dict(sorted(rates.items()))}


def missing_codes(rates: dict, master_list: Path) -> list[str]:
    """Currencies the master list knows about but the provider didn't return."""
    data = json.loads(master_list.read_text())
    have = {rates["base"], *rates["rates"]}
    return sorted(c["code"] for c in data["currency"]["aliases"] if c["code"] not in have)


def write_atomic(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".rates-", suffix=".json")
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default=os.environ.get("RATES_URL", DEFAULT_URL))
    ap.add_argument("--out", type=Path, default=HERE / "rates.json")
    ap.add_argument("--master-list", type=Path, default=HERE / "master-list.sample.json")
    args = ap.parse_args()

    try:
        rates = normalize(fetch(args.url))
    except Exception as e:  # network, JSON or validation: keep the last good file
        print(f"rates job failed, keeping {args.out}: {e}", file=sys.stderr)
        return 1

    missing = missing_codes(rates, args.master_list)
    if missing:
        print(f"warning: no rate for {', '.join(missing)}", file=sys.stderr)

    write_atomic(args.out, rates)
    print(f"wrote {args.out}: {len(rates['rates'])} rates, base {rates['base']}, date {rates['date']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
