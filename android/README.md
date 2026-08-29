# SpaceTrace

An Android file explorer focused on one job: showing you what's actually
taking up space on your device, and what got created recently — including
hidden/dotfiles and directories that the Photos/Files apps hide from you.

## How it works

- **Full-disk scan, not MediaStore.** Android's MediaStore index skips
  dot-prefixed files and many app-cache directories on purpose. SpaceTrace
  instead walks the raw filesystem with `java.io.File`
  (`FileScanner.kt`), which is why it needs the **"All files access"**
  special permission (`MANAGE_EXTERNAL_STORAGE`) rather than the old
  read/write storage permissions.
- **Recent tab** — every file found, grouped by the calendar day it was
  created, newest day first.
- **Storage tab** — biggest folders and biggest individual files, each
  with a proportional size bar, so you can see at a glance what to clear
  out.
- **Browse tab** — a classic folder-by-folder explorer starting from each
  storage volume (internal + SD card/USB if present).
- Long-press any file to delete it (with a confirmation dialog).
- The eye icon in the top bar toggles whether hidden (dot-prefixed) files
  show up in the Recent/Browse lists. The Storage tab's totals always
  include hidden files, since caches and thumbnails are often the biggest
  space users, and hiding them would defeat the app's purpose.

### A real platform limitation: "created" time

Most of Android's shared storage runs on a FUSE-emulated filesystem that
does not reliably track a true file "birth time" — only "last modified".
`FileScanner` tries `java.nio.file.Files.readAttributes(...).creationTime()`
first and falls back to `lastModified()` whenever the OS reports an
unusable value (epoch 0, or a plainly bogus timestamp). In practice, on
many devices "created" and "modified" will show the same value. This is a
platform constraint, not a bug in this app.

## Requirements

- Android 11 (API 30) or newer.
- Android Studio (Koala/2024.1 or newer) with JDK 17.

## Building

```
./gradlew assembleDebug
```

> This sandbox's network policy blocks `dl.google.com`, so the Android
> Gradle Plugin / Android SDK could not be downloaded here and the build
> was **not** compiled or run in this environment. Open the project in
> Android Studio (which will fetch the SDK/AGP normally) to build, run,
> and iterate on it.

## Project layout

```
app/src/main/java/com/spacetrace/app/
  model/       Data classes: FileEntry, FolderStat, ScanResult, ScanProgress
  scan/        FileScanner — the iterative filesystem walk
  util/        Byte/date formatting, permission helpers
  viewmodel/   ScanViewModel — owns scan state, exposes StateFlow to the UI
  ui/
    permission/  "Grant All files access" screen
    recent/      Recent tab (grouped by created day)
    storage/     Storage tab (biggest folders / biggest files)
    browse/      Browse tab (folder navigation)
    common/      Shared row/dialog composables
    theme/       Material 3 theme
```

## Possible next steps

- Persist scan results (Room) so the app doesn't need a full rescan on
  every cold start.
- Search / filter by file type or extension.
- A true treemap visualization for the Storage tab.
- Batch delete (multi-select).
