#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v14-lock-window.xml"

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/v14-lock-window.xml >/dev/null 2>&1 || true; adb pull /sdcard/v14-lock-window.xml "$UI_XML" >/dev/null 2>&1 || true; }
has_text(){ local wanted="$1"; ui_dump; test -s "$UI_XML" || return 1; python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if wanted in n.attrib.get('text','') or wanted in n.attrib.get('content-desc',''):
        raise SystemExit(0)
raise SystemExit(1)
PY
}
wait_text(){ local wanted="$1"; local tries="${2:-30}"; for ((i=1;i<=tries;i++)); do has_text "$wanted" && return 0; sleep .5; done; echo "Timed out waiting for $wanted" >&2; ui_dump; test -f "$UI_XML" && cat "$UI_XML" >&2; return 1; }
activity_stack_has_gate(){ adb shell dumpsys activity activities | grep -q 'ru.egor.meters/.AppUnlockActivity'; }
wait_gate(){ for _ in {1..30}; do activity_stack_has_gate && return 0; sleep .3; done; adb shell dumpsys activity activities >&2; return 1; }
assert_protected_content_hidden(){
  if has_text "Снять → проверить → передать"; then
    echo "Protected launcher content is visible behind lock gate" >&2
    exit 1
  fi
}

# Configure a real device credential so AppUnlockActivity cannot legitimately auto-disable itself.
adb shell locksettings clear --old 1234 >/dev/null 2>&1 || true
adb shell locksettings set-pin 1234 >/dev/null

# Enable the production preference directly. This avoids coupling the regression to settings-screen copy.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell "run-as $PACKAGE_NAME mkdir -p shared_prefs"
cat > "$GITHUB_WORKSPACE/security_settings.xml" <<'XML'
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <boolean name="device_lock" value="true" />
</map>
XML
adb push "$GITHUB_WORKSPACE/security_settings.xml" /data/local/tmp/security_settings.xml >/dev/null
adb shell "run-as $PACKAGE_NAME cp /data/local/tmp/security_settings.xml shared_prefs/security_settings.xml"

# Cold launcher start must put the application-level gate in the task before protected data can be used.
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
wait_gate
# Cancel the system credential sheet. The app gate itself must remain and protected content must not surface.
adb shell input keyevent KEYCODE_BACK >/dev/null
wait_text "Мои счётчики заблокированы" 30
assert_protected_content_hidden

# Back from the gate must background the task rather than reveal the protected launcher.
adb shell input keyevent KEYCODE_BACK >/dev/null
sleep .7
if adb shell dumpsys activity activities | grep -A4 'mResumedActivity' | grep -q 'ru.egor.meters/.V13MainActivity'; then
  echo "Back revealed protected launcher" >&2
  exit 1
fi

# Relaunch/foreground must gate again.
adb shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null
wait_gate
adb shell input keyevent KEYCODE_BACK >/dev/null
wait_text "Мои счётчики заблокированы" 30
assert_protected_content_hidden

# The packaged app shortcut uses TakeReadingsShortcutActivity. Directly starting that exact
# exported shortcut target is the same routing entry proven by android-v20-shortcut.sh; it must
# not bypass the application-level lock or reveal walkthrough data.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.TakeReadingsShortcutActivity" >/dev/null
wait_gate
adb shell input keyevent KEYCODE_BACK >/dev/null
wait_text "Мои счётчики заблокированы" 30
assert_protected_content_hidden
if adb shell dumpsys activity activities | grep -A8 'mResumedActivity' | grep -q 'ru.egor.meters/.V13MainActivity'; then
  echo "Shortcut entry revealed protected launcher behind lock gate" >&2
  exit 1
fi

# Back from a shortcut-originated gate must still background the task rather than expose routed content.
adb shell input keyevent KEYCODE_BACK >/dev/null
sleep .7
if adb shell dumpsys activity activities | grep -A4 'mResumedActivity' | grep -Eq 'ru.egor.meters/\.(V13MainActivity|TakeReadingsShortcutActivity)'; then
  echo "Back from shortcut lock gate revealed protected app content" >&2
  exit 1
fi

# Clean up credential and preference so later CI additions are not contaminated.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/security_settings.xml" >/dev/null 2>&1 || true
adb shell locksettings clear --old 1234 >/dev/null

echo "v2.0 app-lock regression OK: cold start, launcher re-entry/foreground, Back and shortcut entry cannot bypass the application-level gate."
