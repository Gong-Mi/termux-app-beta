#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 <adb-serial> [package] [component]" >&2
    echo "Example: $0 192.0.2.10:5555 com.termux com.termux/.app.TermuxActivity" >&2
    echo "Termux host: invoke as 'bash $0 ...' because Android has no /usr/bin/env." >&2
}

if [[ $# -lt 1 || $# -gt 3 ]]; then
    usage
    exit 2
fi

serial=$1
package=${2:-com.termux}
component=${3:-com.termux/.app.TermuxActivity}
adb_bin=${ADB:-adb}
adb_cmd=("$adb_bin" -s "$serial")

"${adb_cmd[@]}" get-state >/dev/null

uid_line=$("${adb_cmd[@]}" shell cmd package list packages -U "$package" | tr -d '\r')
if [[ "$uid_line" != "package:$package uid:"* ]]; then
    echo "Package not installed: $package" >&2
    exit 1
fi
uid=${uid_line##*uid:}

package_info=$("${adb_cmd[@]}" shell dumpsys package "$package" | tr -d '\r')
if ! grep -q 'DEBUGGABLE' <<<"$package_info"; then
    echo "Package is not debuggable; Developer Options compatibility overrides are unavailable: $package" >&2
    exit 1
fi

target_sdk=$(grep -m1 -o 'targetSdk=[0-9]*' <<<"$package_info" || true)
version_name=$(grep -m1 -o 'versionName=[^ ]*' <<<"$package_info" || true)
version_code=$(grep -m1 -o 'versionCode=[0-9]*' <<<"$package_info" || true)

echo "device=$serial"
echo "package=$package"
echo "uid=$uid"
echo "${version_name:-versionName=unknown}"
echo "${version_code:-versionCode=unknown}"
echo "${target_sdk:-targetSdk=unknown}"
echo "debuggable=true"
echo "compatOverridesMutated=false"

# Bound the evidence window to this cold start. This clears system log buffers,
# but does not change package data or compatibility overrides.
"${adb_cmd[@]}" logcat -c
"${adb_cmd[@]}" shell am force-stop "$package"
"${adb_cmd[@]}" shell am start -W -n "$component" >/dev/null

pid=$("${adb_cmd[@]}" shell pidof -s "$package" | tr -d '\r')
if [[ -z "$pid" ]]; then
    echo "Package did not remain running after cold start: $package" >&2
    exit 1
fi
echo "pid=$pid"

echo "--- compatibility changes reported for uid $uid ---"
compat_logs=$("${adb_cmd[@]}" logcat -d -v threadtime -s CompatChangeReporter:D '*:S' | grep "UID $uid;" || true)
if [[ -n "$compat_logs" ]]; then
    printf '%s\n' "$compat_logs"
else
    echo "(none reported during cold start)"
fi

echo "--- app process fatal errors ---"
app_fatal=$("${adb_cmd[@]}" logcat -d -v threadtime --pid="$pid" | grep -E 'FATAL EXCEPTION|Fatal signal|ANR in' || true)
if [[ -n "$app_fatal" ]]; then
    printf '%s\n' "$app_fatal"
    exit 1
else
    echo "none"
fi

echo "--- package-scoped system errors ---"
system_errors=$("${adb_cmd[@]}" logcat -d -v threadtime -s AndroidRuntime:E ActivityManager:E ActivityTaskManager:E | grep -F "$package" || true)
if [[ -n "$system_errors" ]]; then
    printf '%s\n' "$system_errors"
    exit 1
else
    echo "none"
fi
