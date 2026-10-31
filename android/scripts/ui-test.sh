#!/usr/bin/env bash
# Runs the Compose UI tests (app/src/androidTest) on a running emulator.
# Usage: ui-test.sh <app.apk> <androidTest.apk>. Leaves instrument.txt and ui-test-logcat.txt behind.
set -uo pipefail
APP=$1
TEST=$2

adb install -r "$APP" || exit 1
adb install -r -t "$TEST" || exit 1
adb logcat -c
adb shell am instrument -w io.github.yansenchandra.converter.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
adb logcat -d > ui-test-logcat.txt

# am instrument exits 0 even when tests fail; the summary line tells.
if ! grep -q "^OK (" instrument.txt; then
    echo "UI tests failed"
    exit 1
fi
