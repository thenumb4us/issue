#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/com/issue/app/MainActivity.kt"
BOOT="$ROOT/app/src/main/java/com/issue/app/IssueBootReceiver.kt"
grep -q 'KEY_DEVICE_TOKEN = "device_token"' "$MAIN"
grep -q 'val deviceToken = findString(json, "deviceToken")' "$MAIN"
grep -q 'put("dateOfBirth", dob)' "$MAIN"
grep -q 'IssueSupervisionService.start(applicationContext)' "$MAIN"
grep -q 'remove(KEY_DEVICE_TOKEN)' "$MAIN"
grep -q 'IssueSupervisionService.stop(this)' "$MAIN"
grep -q 'KEY_CHILD_APPROVED' "$MAIN"
grep -q 'prefs().getBoolean(KEY_CHILD_APPROVED, false)' "$MAIN"
grep -q 'device_token' "$BOOT"
echo 'Android device-session wiring checks passed'
