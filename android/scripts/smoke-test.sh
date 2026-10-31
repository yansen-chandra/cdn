#!/usr/bin/env bash
# Installs the APK on a running emulator, launches it and fails if the app crashes.
# Leaves logcat.txt and screenshot.png in the current directory for the CI artifact.
set -uo pipefail
APK=$1
PKG=sg.pixeltools.converter

adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 15
adb exec-out screencap -p > screenshot.png
adb logcat -d > logcat.txt

if grep -q "FATAL EXCEPTION" logcat.txt || ! adb shell pidof "$PKG" > /dev/null; then
    echo "App crashed or is not running:"
    grep -A60 "FATAL EXCEPTION" logcat.txt || tail -100 logcat.txt
    exit 1
fi
echo "App launched and is still running."
