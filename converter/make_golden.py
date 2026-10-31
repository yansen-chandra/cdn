"""Write golden.json: known answers from this reference engine for the Kotlin port.

The Android engine's tests load golden.json and must reproduce every entry.
test_convert.py fails if golden.json is out of date, so after changing the
engine or the master list run:

    python make_golden.py
"""

from __future__ import annotations

import json
from fractions import Fraction
from pathlib import Path

from convert import ConversionError, MasterList, fast_parse, fast_path, fmt, parse_value, process

HERE = Path(__file__).resolve().parent
OUT = HERE / "golden.json"

RATES = {"base": "EUR", "date": "2026-10-30",  # test fixture, not real rates
         "rates": {"SGD": "1.5", "JPY": "160", "USD": "1.1", "GBP": "0.85", "MYR": "5"}}

PARSE_VALUE = ["2.5", "1,000", "1/2", "1 1/2", "-40", "2.5k", "3m", "0", "five", "1/0", "1 2", ""]

FMT = [("1/3", 6), ("2/3", 6), ("1234567", 6), ("0.000123456789", 6), ("-273.15", 6),
       ("4/3", 10), ("100", 6), ("1/7", 3), ("123456789123", 6), ("5/2", 1), ("7/2", 1)]

CONVERT = [
    ("32", "fahrenheit", "celsius", None), ("-40", "celsius", "fahrenheit", None),
    ("0", "kelvin", "celsius", None), ("1", "kati", "tahil", None), ("1", "kati", "pound", None),
    ("1", "chhek", "inch", None), ("1", "mile", "kilometer", None), ("1", "acre", "square-foot", None),
    ("100", "kilometer-per-hour", "mile-per-hour", None), ("1", "tola", "gram", None),
    ("5", "liter-per-100-kilometer", "kilometer-per-liter", None),
    ("30", "mile-per-gallon-us", "liter-per-100-kilometer", None),
    ("2", "cup-us", "gram", "rice-white-uncooked"), ("500", "gram", "cup-us", "flour-all-purpose"),
    ("2", "cup-us", "gram", None), ("5", "kilogram", "centimeter", None),
    ("0", "liter-per-100-kilometer", "kilometer-per-liter", None),
]

RESOLVE = [("gallons", "GB", None), ("gallons", "SG", None), ("cup", "AU", None), ("cup", "SG", None),
           ("tbsp", "AU", None), ("oz", "*", None), ("oz", "SG", "volume"), ("oz", "GB", "volume"),
           ("catty", "CN", None), ("catty", "SG", None), ("$", "SG", None), ("$", "US", None),
           ("¥", "CN", None), ("sing dollar", "*", None), ("rm", "*", None), ("KM", "*", None),
           ("furlongs", "*", None), ("", "*", None)]

TEXTS = [
    ("10 l to gallons", "SG"), ("10 l to gallons", "GB"), ("1 oz to ml", "SG"), ("1 oz to ml", "GB"),
    ("100 sgd to jpy and myr", "SG"), ("$50 to yen", "SG"), ("$50 to yen", "US"), ("3 kati", "SG"),
    ("5 ft to cm", "SG"), ("-40 c to f", "SG"), ("1 1/2 cups to ml", "SG"), ("1 1/2 cups to ml", "AU"),
    ("100 km/h in mph", "SG"), ("5km to mi", "SG"), ("3m to ft", "SG"), ("50 sgd -> jpy", "SG"),
    ("50 sing dollar to yen", "SG"), ("1,000 m to km", "SG"), ("2 kg = lb", "SG"), ("5 ft to cm?", "SG"),
    ("100 usd to sgd, myr & jpy", "SG"), ("1 gb to mb", "SG"), ("30 mpg to l/100km", "GB"),
    ("5'9 in cm", "SG"), ("2 cups rice in grams", "SG"), ("what's the weather", "SG"),
    ("2.5k usd to sgd", "SG"), ("5 kg to cm", "SG"), ("10 sgd", "SG"), ("5 kg to sgd", "SG"),
    ("$50 sgd to jpy", "SG"), ("hello", "SG"), ("", "SG"), ("1 tbsp to ml", "AU"), ("1 catty to g", "CN"),
]

REQUESTS = [
    {"intent": "convert", "domain": "unit", "parts": [{"value": "2", "unit": "cup"}],
     "to": ["gram"], "ingredient": "rice"},
    {"intent": "convert", "domain": "unit", "parts": [{"value": "5", "unit": "foot"}, {"value": "9", "unit": "inch"}],
     "to": ["centimeter"], "ingredient": None},
    {"intent": "convert", "domain": "unit", "parts": [{"value": "2", "unit": "furlongs"}],
     "to": ["meter"], "ingredient": None},
    {"intent": "convert", "domain": "unit", "parts": [{"value": "2", "unit": "cup"}],
     "to": ["gram"], "ingredient": "unobtainium"},
    {"intent": "convert", "domain": "currency", "parts": [{"value": "20", "unit": "GBP"}],
     "to": ["EUR"], "ingredient": None},
    {"intent": "convert", "domain": "unit", "parts": [{"value": "abc", "unit": "meter"}],
     "to": ["foot"], "ingredient": None},
    {"intent": "unsupported", "domain": None, "parts": [], "to": [], "ingredient": None},
    {"intent": "incomplete", "domain": None, "parts": [{"value": "50", "unit": ""}], "to": [], "ingredient": None},
]


def frac(x: Fraction) -> str:
    return f"{x.numerator}/{x.denominator}"


def outcome(res: dict) -> dict:
    return {**res, "results": [{k: v for k, v in r.items() if k != "exact"} for r in res["results"]]}


def build() -> dict:
    ml = MasterList.load()
    g: dict = {"rates": RATES, "parse_value": [], "fmt": [], "convert": [], "resolve": [], "texts": [], "requests": []}

    for t in PARSE_VALUE:
        try:
            g["parse_value"].append({"text": t, "value": frac(parse_value(t))})
        except ConversionError:
            g["parse_value"].append({"text": t, "value": None})

    for x, sig in FMT:
        g["fmt"].append({"value": frac(Fraction(x)), "sig": sig, "text": fmt(Fraction(x), sig)})

    for v, src, dst, ing in CONVERT:
        case = {"value": v, "from": src, "to": dst, "ingredient": ing}
        try:
            case["result"] = frac(ml.convert([(parse_value(v), src)], dst, ing))
        except ConversionError as e:
            case["error"] = str(e)
        g["convert"].append(case)

    for term, region, q in RESOLVE:
        r = ml.resolve(term, region, prefer_quantity=q)
        g["resolve"].append({"term": term, "region": region, "prefer_quantity": q,
                             "result": None if r is None else {"id": r.id, "domain": r.domain, "note": r.note}})

    for text, region in TEXTS:
        req = fast_parse(text)
        g["texts"].append({
            "text": text, "region": region, "fast_parse": req,
            "fast_path": fast_path(text, ml, region) is not None,
            "outcome": None if req is None else outcome(process(req, ml, region, RATES)),
        })

    for req in REQUESTS:
        for region in ("SG", "GB"):
            g["requests"].append({"request": req, "region": region,
                                  "outcome": outcome(process(req, ml, region, RATES))})
    return g


def render() -> str:
    return json.dumps(build(), ensure_ascii=False, indent=1) + "\n"


if __name__ == "__main__":
    OUT.write_text(render(), encoding="utf-8")
    print(f"wrote {OUT}")
