# DayRoute

A day planner for drivers that works on Android Auto. Add the places you need to
go today, say when you need to be at each one, and DayRoute works out the order.
When you plug into the car, it takes you from stop to stop and moves to the next
stop when you arrive.

**Selling point:** many destinations in one day, each with a time window that is
either **strict** (an appointment) or **flexible** (an errand you'd like to do by a certain time).

## How it works for the driver

1. **On the phone:** tap *Add stop*, search an address, and optionally set:
   - *Not before / Not after*: the time window, for example `14:00`–`15:00`
   - *Strict* or *Flexible*: strict times are kept even if it means more driving.
     Flexible times are preferences, and being a little late is fine if it saves a long detour.
   - *Time spent there*: how long you'll stay, so later arrival times are realistic
2. Tap **Plan my day**. You see the order, an arrival time for each stop, any
   waiting (when you'd arrive before a stop opens), and a red warning if a strict
   time can't be met.
3. Tap **Start driving**, on the phone or on the car screen.
4. **In the car:** the DayRoute screen shows a map with numbered pins and the
   next stop at the top. Tap **Start** or **Navigate**, and Google Maps or Waze
   does the turn-by-turn directions.
5. **On arrival:** within about 80 m of the stop, DayRoute marks it visited and
   moves to the next one. It opens navigation to the next stop automatically if
   it can. Otherwise a heads-up alert says *"Arrived at X. Next: Y"* with a
   one-tap **Navigate** button.
6. Running late? Tap **Re-plan**, and the remaining stops are reordered from where you are now.

## Project layout

```
dayroute/
├── planner/   Pure Kotlin, no Android. The brain. Fully unit-tested.
│   ├── Model.kt              Stop, TimeWindow(strict/flexible), ClockTime, DayPlan
│   ├── TimeWindowPlanner.kt  Orders stops around time windows
│   ├── Trip.kt               A day being driven: arrival detection, skip, re-plan
│   └── Geo.kt                Distance + offline drive-time estimate
└── app/       Android app
    ├── data/DayRepository.kt   Single source of truth (phone + car + GPS), saved to JSON
    ├── trip/                   GPS foreground service, arrival notifications, nav hand-off
    ├── car/                    Android Auto screens (Car App Library templates)
    └── phone/                  Jetpack Compose phone UI
```

### The planner

Each possible order of stops is scored by simulating the drive. Every minute of
driving costs 1 point. Waiting costs a little. Lateness costs 5 per minute for a
flexible window and 10,000 per minute for a strict one, so strict lateness only
happens when it can't be avoided, and then the plan reports it.

- **Up to 8 stops:** every order is searched, skipping orders that are already worse than the best found. The result is optimal.
- **More stops:** stops are inserted most-urgent-first, then the order is improved
  by moving single stops and reversing segments. Fast, and good for a typical day.

Drive times come from a `TravelTimeEstimator`. The default is offline: straight-line
distance × 1.35 at 35 km/h. Both the estimator and the planner are interfaces, so
they can be swapped without touching the app. See the roadmap below.

### Why hand off to Google Maps or Waze for turn-by-turn?

Building good turn-by-turn directions (maps, routing, rerouting, lane guidance)
is a huge job, and Android Auto drivers already have excellent navigation apps.
DayRoute's value is **the order of the day and the switch to the next stop**, so
v1 does exactly that and passes the road to Maps or Waze with `CarContext.ACTION_NAVIGATE`.

The car app is declared in the **POI** (points of interest) category, which gets
a host-drawn map with our pins (`PlaceListMapTemplate`) without drawing map tiles ourselves.

> ⚠️ **Before publishing on the Play Store:** Google reviews Android Auto apps per
> category, and POI is meant for things like parking and charging. Check whether a
> multi-stop planner qualifies. If not, the alternative is the **Navigation**
> category: draw our own map and directions with `NavigationTemplate`, for example
> using Google's Navigation SDK, which supports Android Auto. Because the
> planner and trip logic are separate from the screens, that is a contained change.

## Build and run

Requirements: JDK 17+, Android SDK (platform 35). Create `local.properties` with `sdk.dir=/path/to/sdk`.

```bash
./gradlew :planner:test :app:testDebugUnitTest   # all tests
./gradlew :app:assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
```

**Try it without typing addresses:** on an empty day, tap **Try a demo day**. It
creates five stops a few km around you, including two strict appointments.

### Testing on Android Auto

1. Install the debug APK on your phone.
2. **In a car:** in the Android Auto app settings, tap *Version* 10× to unlock
   developer settings, then turn on **Unknown sources**. DayRoute then appears in the car's launcher.
3. **At your desk:** install the *Android Auto Desktop Head Unit* (SDK Manager → SDK Tools), then:
   ```bash
   # In Android Auto developer settings on the phone: "Start head unit server"
   adb forward tcp:5277 tcp:5277
   $ANDROID_HOME/extras/google/auto/desktop-head-unit
   ```

Debug builds accept any car host. Release builds only accept Android Auto and
Android Automotive (`res/values/hosts_allowlist.xml`).

## Roadmap

The seams for these are already in place:

| Version | Feature | Where it plugs in |
|---|---|---|
| v1.1 | Real traffic-aware drive times (Google Routes / Mapbox Matrix API) | Implement `TravelTimeEstimator` |
| v1.1 | Reorder by hand, pin "must be first/last" | `Stop` field + planner cost |
| v1.2 | Save regular places (home, office, school) and weekly templates | `DayRepository` |
| v2 | **AI planning:** "dentist at 10, pick up kids at 3, groceries sometime" turns into stops and windows | A `StopParser` that outputs `Stop`s, plus an LLM-backed `RoutePlanner` that explains its choices. Keep `TimeWindowPlanner` as the fallback and a check on the result |
| v2 | **Voice in the car:** "Skip this stop", "I'm running 20 min late", "Add a gas station" | Car App Library voice input / Assistant app actions map to `DayRepository` calls (`skipCurrent`, `replan`, `addStop`) |
| v3 | Own turn-by-turn (Navigation category) | Replace `Navigation`/`navigateTo` and add a `NavigationTemplate` screen |

## Known limits of v1

- Drive times are estimates (no traffic), so arrival times are approximate. Re-plan when you run late.
- Arrival is detected from the phone's GPS within 80 m of the stop. At a large
  site (a mall, a campus), use **I'm here** on the phone or car screen.
- Whether DayRoute can open the next stop's navigation by itself, without a tap,
  depends on the car host. When it can't, the one-tap heads-up alert appears instead.
- Not yet run on a physical car or in the Desktop Head Unit: the build machine
  had no emulator support. Everything compiles, passes lint, and the logic is covered by tests.
