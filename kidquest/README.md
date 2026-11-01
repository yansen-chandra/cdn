# KidQuest ⭐

A mobile-first web app for managing children's daily schedules with a configurable star-points reward system.

## Features

- **Multiple child profiles**: each child gets a name, an emoji avatar and a theme colour.
- **Daily schedule**: tasks are grouped into Morning / Afternoon / Evening, with an optional time and repeat days (every day, weekdays, weekends or custom). A week strip shows how each day went.
- **Star points**: the parent sets how many stars each task is worth. Kids tap a task to complete it and get confetti, a sound, and a stars counter that goes up. Finishing every task in a day shows a celebration.
- **Rewards shop**: the parent creates rewards with a star cost and maps each one to specific children. Kids see a progress bar towards each reward and can redeem it when they have enough stars.
- **Parent approval** (optional): redeemed rewards wait in the parent **Inbox**. Declining refunds the stars.
- **Bonus / penalty stars**: give or take stars with a reason.
- **Trophies & streaks**: day streaks, stats and unlockable badges.
- **Parent PIN**: a 4-digit PIN keeps kids out of the parent zone. It's a child lock, not real security.
- **Backup**: export or import all data as JSON.
- **Installable PWA**: add to the home screen. It works offline after the first load.

## Running

It's a static site with no build step. Serve the folder with any static server:

```bash
cd kidquest
python3 -m http.server 8000
# open http://localhost:8000
```

To host it, enable GitHub Pages on this repo. The app will be at `/kidquest/`.

## Data

Everything is stored in the browser's `localStorage` on the device (key `kidquest:v1`). Nothing is sent to a server. Use **Settings → Export** to move data to another device.

## Files

| File | Purpose |
| --- | --- |
| `index.html` | App shell |
| `app.js` | State, routing, views and interactions |
| `styles.css` | Mobile-first styles and animations |
| `manifest.webmanifest`, `sw.js`, `icons/` | PWA install and offline support |

## Versioning

The version is set in `APP_VERSION` at the top of `app.js` and shown at the bottom of every screen and in **Settings → About**. When releasing, bump it and set the same value in `CACHE` in `sw.js`.

| Version | Changes |
| --- | --- |
| 1.3.0 | Import sheet: choose file or paste text, import via `?import=` link, clearer import errors, always fetch latest app files |
| 1.2.0 | Version number in footer and Settings |
| 1.1.1 | Fix backup import doing nothing on Android |
| 1.1.0 | Sample family backup, restore from the welcome screen, more reward icons |
| 1.0.0 | First release |
