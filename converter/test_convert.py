"""Known-answer tests for the master list and engine. Run: python -m pytest -q"""

import json
from fractions import Fraction
from pathlib import Path

import pytest

from convert import (ConversionError, MasterList, convert_currency, fast_parse,
                     fast_path, fmt, parse_value, process)

HERE = Path(__file__).resolve().parent
ML = MasterList.load()
RATES = {"base": "EUR", "date": "2026-10-30",
         "rates": {"SGD": "1.5", "JPY": "160", "USD": "1.1", "GBP": "0.85", "MYR": "5"}}  # test fixture, not real rates


def conv(value, src, dst, ingredient=None):
    return ML.convert([(parse_value(value), src)], dst, ingredient)


def run(text, region="*", rates=None):
    req = fast_parse(text)
    assert req is not None, f"fast parser missed {text!r}"
    return process(req, ML, region, rates)


# ---------------- master list integrity -----------------------------------

def test_master_list_is_consistent():
    assert ML.validate() == []


def test_master_list_matches_schema():
    jsonschema = pytest.importorskip("jsonschema")
    schema = json.loads((HERE / "master-list.schema.json").read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    errors = sorted(jsonschema.Draft202012Validator(schema).iter_errors(ML.data), key=str)
    assert not errors, "\n".join(e.message for e in errors[:10])


# ---------------- numbers --------------------------------------------------

@pytest.mark.parametrize("text,expected", [
    ("2.5", Fraction(5, 2)), ("1,000", 1000), ("1/2", Fraction(1, 2)),
    ("1 1/2", Fraction(3, 2)), ("-40", -40), ("2.5k", 2500), ("3m", 3_000_000),
])
def test_parse_value(text, expected):
    assert parse_value(text) == expected


def test_parse_value_rejects_garbage():
    with pytest.raises(ConversionError):
        parse_value("five")


# ---------------- exact conversions ----------------------------------------

def test_temperature():
    assert conv("32", "fahrenheit", "celsius") == 0
    assert conv("212", "fahrenheit", "celsius") == 100
    assert conv("-40", "celsius", "fahrenheit") == -40
    assert conv("0", "kelvin", "celsius") == Fraction("-273.15")


def test_singapore_customary_units():
    assert conv("1", "kati", "tahil") == 16
    assert conv("1", "pikul", "kati") == 100
    assert fmt(conv("1", "kati", "kilogram")) == "0.60479"
    assert fmt(conv("1", "kati", "pound"), 10) == "1.333333333"
    assert fmt(conv("1", "chhek", "inch")) == "14.75"


def test_length_area_speed():
    assert conv("1", "mile", "kilometer") == Fraction("1.609344")
    assert conv("1", "acre", "square-foot") == 43560
    assert fmt(conv("100", "kilometer-per-hour", "mile-per-hour")) == "62.1371"
    assert fmt(conv("1", "tola", "gram")) == "11.6638"


def test_compound_height():
    parts = [(Fraction(5), "foot"), (Fraction(9), "inch")]
    assert ML.convert(parts, "centimeter") == Fraction("175.26")


def test_fuel_economy_reciprocal():
    assert conv("5", "liter-per-100-kilometer", "kilometer-per-liter") == 20
    assert fmt(conv("30", "mile-per-gallon-us", "liter-per-100-kilometer")) == "7.84049"
    assert fmt(conv("30", "mile-per-gallon-imperial", "liter-per-100-kilometer")) == "9.41603"


def test_volume_to_mass_needs_ingredient():
    with pytest.raises(ConversionError):
        conv("2", "cup-us", "gram")
    # 2 US cups * 236.5882365 mL * 0.78 g/mL
    assert fmt(conv("2", "cup-us", "gram", "rice-white-uncooked")) == "369.078"
    assert fmt(conv("500", "gram", "cup-us", "flour-all-purpose")) == "3.9875"


def test_incompatible_units():
    with pytest.raises(ConversionError):
        conv("5", "kilogram", "centimeter")


# ---------------- ambiguity -------------------------------------------------

def test_gallon_follows_region():
    assert ML.resolve("gallons", "GB").id == "gallon-imperial"
    us = ML.resolve("gallons", "SG")
    assert us.id == "gallon-us" and us.note == "Assumed US gallon"


def test_cup_and_tablespoon_regions():
    assert ML.resolve("cup", "AU").id == "cup-metric"
    assert ML.resolve("cup", "SG").id == "cup-us"
    assert ML.resolve("tbsp", "AU").id == "tablespoon-au"
    assert ML.resolve("tbsp", "SG").id == "tablespoon-metric"


def test_oz_switches_to_fluid_ounce_for_volume_targets():
    assert ML.resolve("oz").id == "ounce"
    assert ML.resolve("oz", "SG", prefer_quantity="volume").id == "fluid-ounce-us"
    assert ML.resolve("oz", "GB", prefer_quantity="volume").id == "fluid-ounce-imperial"


def test_catty_depends_on_region():
    assert ML.resolve("catty", "CN").id == "jin"
    assert ML.resolve("catty", "SG").id == "kati"


def test_currency_symbols():
    assert ML.resolve("$", "SG").id == "SGD"
    assert ML.resolve("$", "US").id == "USD"
    assert ML.resolve("¥", "CN").id == "CNY"
    assert ML.resolve("sing dollar").id == "SGD"
    assert ML.resolve("rm").id == "MYR"


# ---------------- end to end -----------------------------------------------

def test_e2e_units_with_note():
    res = run("10 l to gallons", region="SG")
    assert res["results"][0]["value"] == "2.64172"
    assert "Assumed US gallon" in res["notes"]
    res = run("10 l to gallons", region="GB")
    assert res["results"][0]["value"] == "2.19969"


def test_e2e_oz_to_ml():
    res = run("1 oz to ml", region="SG")
    assert res["results"][0]["value"] == "29.5735"


def test_e2e_multiple_currency_targets():
    res = run("100 sgd to jpy and myr", rates=RATES)
    assert [r["value"] for r in res["results"]] == ["10666.7", "333.333"]
    assert any("Not for trading" in n for n in res["notes"])


def test_e2e_symbol_prefix():
    res = run("$50 to yen", region="SG", rates=RATES)
    assert res["results"][0]["value"] == "5333.33"
    assert "Assumed SGD" in res["notes"]


def test_e2e_no_target_uses_common_targets():
    res = run("3 kati", region="SG")
    assert [r["unit"] for r in res["results"]] == ["kilogram", "gram", "pound"]
    assert any("Reference only" in n for n in res["notes"])


def test_e2e_llm_style_request_with_ingredient():
    req = {"intent": "convert", "domain": "unit", "parts": [{"value": "2", "unit": "cup"}],
           "to": ["gram"], "ingredient": "rice"}
    res = process(req, ML, "SG")
    assert res["results"][0]["value"] == "369.078"
    assert any("Approximate" in n for n in res["notes"])


def test_e2e_unresolved_terms_are_reported():
    req = {"intent": "convert", "domain": "unit", "parts": [{"value": "2", "unit": "cubits"}],
           "to": ["meter"], "ingredient": None}
    assert process(req, ML)["unresolved"] == ["cubits"]


def test_e2e_unsupported():
    req = {"intent": "unsupported", "domain": None, "parts": [], "to": [], "ingredient": None}
    assert process(req, ML)["error"] == "unsupported"


# ---------------- fast parser routing --------------------------------------

@pytest.mark.parametrize("text", ["5 ft to cm", "-40 c to f", "1 1/2 cups to ml", "100 km/h in mph",
                                  "5km to mi", "3m to ft", "50 sgd -> jpy", "50 sing dollar to yen", "3 kati"])
def test_fast_path_handles_clean_input(text):
    assert fast_path(text, ML, "SG") is not None


def test_fast_parse_keeps_unit_letters():
    assert fast_parse("5km to mi")["parts"] == [{"value": "5", "unit": "km"}]
    assert fast_parse("3m to ft")["parts"] == [{"value": "3", "unit": "m"}]


@pytest.mark.parametrize("text", ["5'9 in cm", "how many grams is 2 cups of rice",
                                  "2 cups rice in grams", "what's the weather", "2.5k usd to sgd"])
def test_fast_path_defers_to_llm(text):
    assert fast_path(text, ML, "SG") is None


def test_currency_math():
    assert convert_currency(Fraction(1), "EUR", "SGD", RATES) == Fraction("1.5")
    assert convert_currency(Fraction(150), "SGD", "EUR", RATES) == 100


# ---------------- Kotlin port oracle ---------------------------------------

def test_golden_file_is_current():
    from make_golden import OUT, render
    assert OUT.read_text(encoding="utf-8") == render(), "run: python make_golden.py"


def test_every_published_rate_currency_is_in_master_list():
    """Search and the parser only know master-list currencies, so each rate needs an entry."""
    rates = json.loads((HERE / "rates.json").read_text(encoding="utf-8"))
    codes = {c["code"] for c in ML.data["currency"]["aliases"]}
    assert sorted({rates["base"], *rates["rates"]} - codes) == []


@pytest.mark.parametrize("term,code", [("chf", "CHF"), ("swiss franc", "CHF"), ("francs", "CHF"),
                                       ("canadian dollar", "CAD"), ("rand", "ZAR"), ("zloty", "PLN")])
def test_more_currency_aliases(term, code):
    assert ML.resolve(term).id == code


# ---------------- more quantities (time, pressure, energy, power, ...) -------

@pytest.mark.parametrize("value,src,dst,expected", [
    ("2", "hour", "minute", "120"), ("1", "day", "second", "86400"), ("1", "week", "hour", "168"),
    ("1", "year", "day", "365.242"), ("90", "minute", "hour", "1.5"),
    ("1", "atmosphere", "psi", "14.6959"), ("1", "bar", "kilopascal", "100"), ("760", "torr", "atmosphere", "1"),
    ("1", "kilowatt-hour", "kilojoule", "3600"), ("1", "kilocalorie", "kilojoule", "4.184"),
    ("1", "btu", "joule", "1055.06"), ("1", "horsepower", "watt", "745.7"),
    ("1", "metric-horsepower", "watt", "735.499"), ("1", "pound-force", "newton", "4.44822"),
    ("1", "kilogram-force", "newton", "9.80665"), ("3000", "revolution-per-minute", "hertz", "50"),
    ("1", "radian", "degree", "57.2958"), ("180", "degree", "radian", "3.14159"), ("1", "turn", "gradian", "400"),
    ("100", "megabit-per-second", "megabyte-per-second", "12.5"), ("1", "terabyte", "gigabyte", "1000"),
    ("1", "light-year", "kilometer", "9460730000000"), ("1", "furlong", "meter", "201.168"),
    ("1", "troy-ounce", "gram", "31.1035"), ("1", "carat", "milligram", "200"),
    ("1", "square-mile", "square-kilometer", "2.58999"), ("1", "cubic-foot", "liter", "28.3168"),
    ("1", "pint-imperial", "milliliter", "568.261"), ("1", "pint-us", "milliliter", "473.176"),
    ("0", "rankine", "kelvin", "0"), ("491.67", "rankine", "celsius", "0"),
])
def test_more_quantities(value, src, dst, expected):
    assert fmt(conv(value, src, dst)) == expected


def test_e2e_time_and_regional_pints():
    assert run("2 hours to minutes")["results"][0]["value"] == "120"
    assert run("1 pint to ml", region="GB")["results"][0]["value"] == "568.261"
    res = run("1 pint to ml", region="US")
    assert res["results"][0]["value"] == "473.176" and "Assumed US pint" in res["notes"]
    assert "Assumed food Calorie (kcal)" in run("500 calories to kj")["notes"]
