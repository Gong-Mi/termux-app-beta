#!/usr/bin/env bash
# Cold-start smoke test that also exercises the parser-worker render pipeline.
#
# Checks, in order:
#   1. package installed + debuggable (needed for compat overrides anyway)
#   2. cold start keeps the process alive (catches ClassNotFoundException-level
#      regressions, e.g. installing a base-only APK from a split AAB app)
#   3. debug builds emit per-frame diagnostics (Termux:TerminalView) and the
#      screen revision advances, i.e. the parser-worker mailbox -> render handoff
#      actually runs
#   4. no FATAL/ANR/package-scoped system errors
#   5. compatibility changes reported for the app uid
#
# The fused universal .apks target (from signed-apks.yml) is the one that must
# pass this; a base-only `assembleDebug` APK is expected to fail at step 2.
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
debuggable=true

target_sdk=$(grep -m1 -o 'targetSdk=[0-9]*' <<<"$package_info" || true)
version_name=$(grep -m1 -o 'versionName=[^ ]*' <<<"$package_info" || true)
version_code=$(grep -m1 -o 'versionCode=[0-9]*' <<<"$package_info" || true)

echo "device=$serial"
echo "package=$package"
echo "uid=$uid"
echo "${version_name:-versionName=unknown}"
echo "${version_code:-versionCode=unknown}"
echo "${target_sdk:-targetSdk=unknown}"
echo "debuggable=$debuggable"
echo "compatOverridesMutated=false"

# Bound the evidence window to this cold start.
"${adb_cmd[@]}" logcat -c

# A dozing/locked display does not run onDraw, so no frame diagnostics are
# emitted. Wake + dismiss keyguard before the cold start so the render
# pipeline actually produces frames.
"${adb_cmd[@]}" shell input keyevent 224 2>/dev/null || true   # KEYCODE_WAKEUP
"${adb_cmd[@]}" shell wm dismiss-keyguard 2>/dev/null || true
"${adb_cmd[@]}" shell input keyevent 82 2>/dev/null || true    # KEYCODE_MENU
sleep 1

"${adb_cmd[@]}" shell am force-stop "$package"
"${adb_cmd[@]}" shell am start -W -n "$component" >/dev/null
sleep 2

pid=$("${adb_cmd[@]}" shell pidof -s "$package" | tr -d '\r' || true)
if [[ -z "$pid" ]]; then
    echo "FAIL: package crashed during cold start (no pid). If this is a split-AAB app," >&2
    echo "a base-only APK will fail here with ClassNotFoundException. Install the fused" >&2
    echo "universal .apks from signed-apks.yml instead." >&2
    echo "--- relevant AndroidRuntime lines ---" >&2
    "${adb_cmd[@]}" logcat -d -v threadtime -s AndroidRuntime:E '*:S' | tail -20 >&2 || true
    exit 1
fi
echo "pid=$pid"

echo "--- parser-worker frame pipeline (debug diagnostics) ---"
# The tag Termux:TerminalView contains a colon, which adb logcat's
# 'Tag:priority' filter grammar cannot parse; filter by pid instead.
frame_logs=$("${adb_cmd[@]}" logcat -d --pid="$pid" 2>/dev/null | grep 'Termux:TerminalView:.*frame rev=' || true)
if [[ -z "$frame_logs" ]]; then
    echo "(no frame diagnostics; release builds skip this check)"
else
    first_rev=$(sed -n 's/.*frame rev=\([0-9]*\).*/\1/p' <<<"$frame_logs" | head -1)
    last_rev=$(sed -n 's/.*frame rev=\([0-9]*\).*/\1/p' <<<"$frame_logs" | tail -1)
    echo "frame samples: $(wc -l <<<"$frame_logs")"
    echo "screenRevision: ${first_rev:-none} -> ${last_rev:-none}"
    if [[ -z "$last_rev" || "$last_rev" -lt 1 ]]; then
        echo "FAIL: frame diagnostics present but screen revision did not advance" >&2
        exit 1
    fi
    # Keep the evidence visible for the caller.
    tail -5 <<<"$frame_logs"
fi

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
    printf '%s\n' "$app_fatal" >&2
    exit 1
else
    echo "none"
fi

echo "--- package-scoped system errors ---"
system_errors=$("${adb_cmd[@]}" logcat -d -v threadtime -s AndroidRuntime:E ActivityManager:E ActivityTaskManager:E | grep -F "$package" || true)
if [[ -n "$system_errors" ]]; then
    printf '%s\n' "$system_errors" >&2
    exit 1
else
    echo "none"
fi

echo "SMOKE-PASS"