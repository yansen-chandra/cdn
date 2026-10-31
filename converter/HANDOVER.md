# Handover: AI unit & currency converter (Android)

Read this first, then `parse-prompt.md`, then run the tests. Everything here was built and tested in a Claude chat session on 2026-10-31.

## Goal

An Android utility app where users type a conversion in plain language ("50 sing dollar to yen", "2 cups rice in grams", "my height 5'9 in cm") and get an exact answer. Units come from a curated master list; currency rates come from a reliable online source.

## Decision update (31 Oct): free = intuitive UI, AI = premium

This overrides any earlier note about free daily AI queries.

**Free tier (no LLM, zero running cost per user)**
- The free app must be fully usable without AI. Invest in the UI, not in AI giveaways.
- The fast parser (`fast_path()`) stays free: typing "5 ft to cm" or "100 sgd to myr" is deterministic code, not AI.
- Ads allowed in free tier.

**Premium (one-time unlock, test $0.99 vs $1.99; check Play Console's minimum tier for SG)**
- AI natural-language input: messy phrasing, compound amounts (5'9"), cooking volume↔weight with ingredients.
- Candidate premium extras: camera price scan (on-device ML Kit text recognition, no server cost), no ads, unlimited history/favourites.
- LLM calls only for premium users, so no free-tier abuse. Still keep a quiet fair-use cap (e.g. 100 AI calls/day) against scripts.

**LLM backend (cheap/free to start)**
- Cloudflare Worker proxy holds keys and checks premium entitlement (verify the Play purchase token server-side). Never ship an API key in the APK.
- First choice: Cloudflare Workers AI (10,000 free neurons/day; Llama 3.2 1B ≈ 3.4 neurons per parse ≈ 2,900 parses/day). Backup: Gemini API free tier (quota shared per project, no guaranteed capacity, free-tier inputs may be reviewed by Google: disclose in privacy policy; not allowed for EEA/UK/CH users). Move to paid Flash-Lite when quota is hit regularly.
- Later option: Gemma 3 270M on-device, fine-tuned on the parse examples (free, offline, adds app size).

**Free-tier UI principles**
1. One screen. Big amount field with number pad, "from" and "to" unit chips, swap button, result updates live as you type. No "Convert" button.
2. Smart input box on top (fast parser) with autocomplete chips as the user types ("5 f" → ft, °F).
3. Show several results at once (the quantity's `common_targets`), each tap-to-copy.
4. Unit picker = search by any alias ("lbs", "sing dollar", "kati") + category tiles; recents and favourite pairs first.
5. Region-aware defaults (US vs UK gallon, $ = SGD in SG) shown as a small "Assumed US gallon" chip; tap to switch.
6. Home currency from device locale; rates cached offline, date always visible.
7. Home-screen widget for a favourite pair (e.g. SGD→MYR, kati→kg).
8. Tease premium only at the point of need: if the fast parser fails on typed text, offer "Try AI parsing" (premium) next to the manual picker.

## Why this app (ASO research, Google Play US, Sep–Oct 2026)

Source: Sonar keyword pages (trysonar.app/keyword-difficulty/<keyword>/android/us). They give difficulty only, no search popularity; review counts of the top 10 were used as a rough demand signal.

| Keyword | Difficulty | Finding |
|---|---|---|
| unit converter | 46 | Top 2 apps ~190–220K reviews, but #3 has 100 reviews, #9 is 3.2★, #10 has none |
| currency converter | 45 | #1 has 433K reviews; #9 and #10 have no reviews |
| calculator app | 83 | App with no reviews at #7; only 1/10 has the phrase in the title |
| qr code reader | 79 | 0/10 exact title match, but giants dominate |
| pdf scanner / file manager | 83 / 87 | Skip: every slot is strong |

Plan: title like "Unit & Currency Converter – AI" to target both 45/46 keywords. "AI" is a feature, not the main keyword. Re-check for Singapore if SG is a target market.

## Architecture decisions (agreed with Cy)

1. **The LLM extracts, never calculates.** It returns a JSON request; deterministic code does all math.
2. **Fast path first.** A regex parser handles clean input ("5 ft to cm") offline and free. The LLM is called only when the fast path can't resolve every term (`fast_path()` returns `None`).
3. **Ambiguity is decided by code, not the LLM.** The LLM copies ambiguous terms ("gallons", "cup", "$", "catty") as written; the resolver picks by user region and shows a note ("Assumed US gallon — tap to change"). If the target is a volume, "oz" becomes fluid ounce.
4. **Exact math.** All factors stored as strings (decimal or fraction like `1852/3600`); engine uses rationals. Kinds: `linear` (value×factor), `affine` (temperature, ×factor+offset), `reciprocal` (L/100 km).
5. **Currency: Frankfurter** (frankfurter.dev). Free, no API key, commercial use allowed, 228 currencies blended from 106 central banks (ECB origin), daily updates, caching encouraged, self-hostable via Docker. **A server/CDN job fetches once a day and publishes one rates JSON; phones never call the provider.** Always show the rate date and "Not for trading". If hourly rates are ever needed: Open Exchange Rates free tier (1,000 req/month, hourly, USD base), but its pricing page is dated 2017, so verify.
6. **Units: start from Unicode CLDR `units.xml`** (factor + offset to base unit, localized names), then add curated aliases, regional units and ambiguity rules.
7. **Regional differentiator:** Singapore/Malaysia kati, tahil, pikul (Weights and Measures Act 1975, Third Schedule: kati = 16 tahil = 1⅓ lb), CN jin (500 g), tola (180 grains). Only metric is permitted for trade in Singapore, so these show a "Reference only" note.
8. **LLM choice:** cloud small/cheap model with structured output, temperature 0, cached system-prompt prefix. On-device Gemini Nano (ML Kit GenAI Prompt API) was still alpha and best on Pixel 10 at the time, so it's a later option, not the default.

## Files

| File | What it is |
|---|---|
| `master-list.schema.json` | JSON Schema (draft 2020-12) for the master list |
| `master-list.sample.json` | Sample master list: 8 quantities, ~60 units, 34 ambiguity rules, 10 ingredient densities, 17 currencies with aliases |
| `parse-prompt.md` | LLM system prompt, structured-output schema, few-shot examples, post-processing steps |
| `convert.py` | Reference engine: `parse_value`, `MasterList` (load/validate/resolve/convert), `convert_currency`, `fast_parse`, `fast_path`, `process` (end to end), CLI |
| `test_convert.py` | 46 known-answer tests (all passing) |
| `fetch_rates.py` | Daily rates job: Frankfurter → validated `rates.json` (`{base, date, rates}`, rates as exact decimal strings); keeps the last good file on failure |
| `test_fetch_rates.py` | 13 offline tests for the rates job |

Run:

```bash
python -m pip install pytest jsonschema
python -m pytest -q
python convert.py "10 l to gallons" --region GB
python convert.py "50 sgd to jpy" --rates rates.json   # rates: {"base","date","rates":{CODE: rate}}
```

`convert.py` is the spec and test oracle. The app engine should be a Kotlin port with the same behaviour and the same known-answer tests.

## Status

- Done: schema, sample data, parsing prompt, reference engine, tests (46 passing), schema validation and master-list integrity checks (`MasterList.validate()`: duplicate ids, alias collisions, base units, ambiguity candidates).
- Done: rates job (Next step 1). `.github/workflows/converter-rates.yml` runs `fetch_rates.py` on weekdays at 16:37 UTC and commits `converter/rates.json` when it changes; `converter-tests.yml` runs the tests on every change under `converter/`. Served from this repo via jsDelivr: `https://cdn.jsdelivr.net/gh/yansen-chandra/cdn@master/converter/rates.json`.
- Rates job caveats: default endpoint is Frankfurter v1 (`api.frankfurter.dev/v1/latest?base=EUR`, ECB set, about 30 currencies), which has no PKR or LKR (the job warns about these). The 228-currency feed mentioned above may be a newer endpoint: confirm it at frankfurter.dev and set it via `--url` or `RATES_URL`. The endpoint was not reachable from the build sandbox, so the first live run is in GitHub Actions.
- Not done: Next steps 2–7.

## Next steps (suggested order)

1. **Rates job.** Small scheduled function that pulls Frankfurter daily and writes `rates.json` (`{base, date, rates}`) to a CDN. Confirm the current endpoint and terms at frankfurter.dev before coding. Keep the last good file if a fetch fails.
2. **Expand the master list from CLDR.** Script to import the latest `units.xml` (not the HTML chart) into the schema, then merge the curated aliases/regional units from the sample. Run `validate()` and the schema test in CI on every change.
3. **LLM integration.** Pick provider and model, wire structured output with the schema in `parse-prompt.md`, turn the example table into regression tests, log unresolved terms (anonymously) to grow aliases.
4. **Kotlin engine port** for Android, sharing the same master-list JSON (bundled + updatable by version). Port `test_convert.py` cases to JUnit.
5. **UI:** build the free-tier UI per "Decision update" above first; then Play Billing one-time unlock + server-side purchase verification; then premium AI input via the Cloudflare Worker proxy.
6. **Gaps to fill:** decimal-comma locales ("2,5"), more units (pint, quart, psi, bar, kWh, calories, cooking "pinch"), verify ingredient densities, "pound" as GBP only via LLM context.
7. **ASO listing:** title/short description targeting "unit converter" + "currency converter"; screenshots showing natural-language input and kati/tahil support.

## Open questions for Cy

- Target markets first (SG only, SEA, or global)? Affects region defaults and ASO country.
- LLM provider and monthly budget per active user.
- Where the rates job runs (personal cloud account, not GCC).
- App name and brand (package id is io.github.yansenchandra.converter).

## Sources used

- Sonar keyword pages, Google Play US: unit-converter, currency-converter, calculator-app, qr-code-reader, tip-calculator, pdf-scanner, file-manager (trysonar.app/keyword-difficulty/...)
- Frankfurter: https://frankfurter.dev
- Open Exchange Rates plans: https://support.openexchangerates.org/article/69-plans-pricing-guide
- ML Kit Prompt API: https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences
- CLDR unit conversions: https://www.unicode.org/cldr/charts/41/supplemental/unit_conversions.html (use `units.xml` for data)
- Singapore units: https://en.wikipedia.org/wiki/Singaporean_units_of_measurement (Weights and Measures Act 1975, Third Schedule)

## Android app (`android/`, started 31 Oct)

- `engine/`: Kotlin port of `convert.py` (pure JVM, exact `Rational` on `BigInteger`). `GoldenTest` replays every answer in `converter/golden.json`; regenerate that file with `python make_golden.py` after engine or master-list changes (a Python test fails if it is stale).
- `app/`: Jetpack Compose free-tier UI: smart input with fast parser + autocomplete chips, category chips, amount/from/to with swap, live result and common targets (tap to copy), unit picker searching every alias, cached currency rates with date. Master list is bundled from `converter/master-list.sample.json` at build time.
- Build: `.github/workflows/android-apk.yml` runs engine tests and `assembleDebug` on every push; download `converter-debug-apk` from the run. `app/debug.keystore` is a checked-in debug key so CI builds install over each other; release needs a real key.
- Rates URL: jsDelivr then raw GitHub for `converter/rates.json` on the branch given by `-PratesBranch` (CI passes the current branch).
- Not done yet: home-screen widget, favourite pairs, region switcher chip, ads, Play Billing, premium AI input.
- Rates on the phone (changed 31 Oct, owner's call): Refresh, and the automatic refresh when the saved rates are older than 6 h, download straight from Frankfurter (`api.frankfurter.dev/v1/latest?base=EUR`), validate with `Rates.fromProvider` and save to the app cache. The repo's `rates.json` is only a backup when Frankfurter is unreachable. A download never replaces newer cached rates.
- Master list 2026.10.3 (31 Oct): 178 units in 16 categories. Added Time, Pressure, Energy, Power, Force, Frequency, Angle and Data speed, plus more length, mass, area, volume (pints/quarts by region), speed, Rankine and TB/PB/bits. "calorie(s)" means the food Calorie (kcal); the small calorie's id is `small-calorie`. Month and year use the average Gregorian length and are marked approximate.
- App 0.2.0 (31 Oct): readable results (grouped digits, scientific notation outside 10⁻⁶–10¹²; copy gives plain digits), decimal comma in both inputs, tappable variant chips for ambiguous terms (`process()` now returns `assumptions`), category grid, recent categories first, favourite and recent pair chips, history sheet, stale-rates warning (> 4 days), currencies without a rate hidden, home-screen widget (Glance) for the first favourite pair, UI text in `strings.xml`, screen-reader labels.
- CI (`android-apk.yml`): engine tests; debug, release (R8) APKs and AAB; Compose UI tests on an API 34 emulator; launch check of the release build. Release is signed with the upload key from secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, else with the debug key. `versionCode` = workflow run number.
