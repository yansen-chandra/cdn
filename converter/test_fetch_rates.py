"""Tests for the rates job. No network: the provider response is a fixture."""

import json
from datetime import date
from fractions import Fraction
from pathlib import Path

import pytest

from convert import convert_currency, parse_value
from fetch_rates import RatesError, missing_codes, normalize, write_atomic

HERE = Path(__file__).resolve().parent
TODAY = date(2026, 10, 31)
CODES = ["AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "GBP", "HKD", "HUF", "IDR",
         "INR", "JPY", "KRW", "MXN", "MYR", "NOK", "PHP", "PLN", "SEK", "SGD", "THB", "USD"]


def payload(**over):
    p = {"amount": 1.0, "base": "EUR", "date": "2026-10-30",
         "rates": {c: 1.5 for c in CODES}}  # test fixture, not real rates
    p["rates"].update({"JPY": 160.0, "IDR": 17000.0, "KRW": 1.5e3, "SGD": 1.5})
    p.update(over)
    return p


def test_normalize_shape_and_strings():
    r = normalize(payload(), TODAY)
    assert r["base"] == "EUR" and r["date"] == "2026-10-30"
    assert list(r["rates"]) == sorted(r["rates"])
    assert all(isinstance(v, str) for v in r["rates"].values())
    assert r["rates"]["KRW"] == "1500"  # no exponent form
    assert r["rates"]["SGD"] == "1.5"


def test_output_works_with_engine():
    r = normalize(payload(), TODAY)
    assert convert_currency(parse_value("3"), "SGD", "JPY", r) == Fraction(320)


@pytest.mark.parametrize("bad", [
    {"base": "eur"},
    {"date": "yesterday"},
    {"date": "2026-11-05"},  # future
    {"date": "2026-10-01"},  # stale
    {"rates": {"SGD": 1.5}},  # too few
    {"rates": {**{c: 1.5 for c in CODES}, "SGD": 0}},
    {"rates": {**{c: 1.5 for c in CODES}, "SGD": "abc"}},
    {"rates": {**{c: 1.5 for c in CODES}, "sgd": 1.5}},
])
def test_normalize_rejects_bad_payloads(bad):
    with pytest.raises(RatesError):
        normalize(payload(**bad), TODAY)


def test_missing_fields():
    with pytest.raises(RatesError):
        normalize({"base": "EUR"}, TODAY)


def test_missing_codes_against_master_list():
    r = normalize(payload(), TODAY)
    assert missing_codes(r, HERE / "master-list.sample.json") == ["LKR", "PKR"]


def test_write_atomic(tmp_path):
    out = tmp_path / "sub" / "rates.json"
    write_atomic(out, {"base": "EUR", "date": "2026-10-30", "rates": {"SGD": "1.5"}})
    assert json.loads(out.read_text())["rates"]["SGD"] == "1.5"
    assert [p.name for p in out.parent.iterdir()] == ["rates.json"]
