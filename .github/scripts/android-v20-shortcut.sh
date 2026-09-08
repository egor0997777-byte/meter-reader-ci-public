#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v20-shortcut-window.xml"

ui_dump(){
  adb shell uiautomator dump --compressed /sdcard/v20-shortcut-window.xml >/dev/null
  adb pull /sdcard/v20-shortcut-window.xml "$UI_XML" >/dev/null
}
has_text(){
  local wanted="$1"
  ui_dump
  python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for node in ET.parse(path).getroot().iter('node'):
    if wanted in node.attrib.get('text','') or wanted in node.attrib.get('content-desc',''):
        raise SystemExit(0)
raise SystemExit(1)
PY
}
wait_text(){
  local wanted="$1" tries="${2:-40}"
  for ((i=1;i<=tries;i++)); do
    has_text "$wanted" && return 0
    sleep .5
  done
  echo "Timed out waiting for shortcut target UI: $wanted" >&2
  ui_dump
  cat "$UI_XML" >&2
  return 1
}

# The preceding monthly-flow regression leaves a real Home dataset in Room.
# Verify the static launcher shortcut is packaged and points at the dedicated routing activity.
AAPT=$(find "${ANDROID_HOME:?}/build-tools" -type f -name aapt -perm -111 | sort -V | tail -n 1)
"$AAPT" dump xmltree "$GITHUB_WORKSPACE/$APK_PATH" res/xml/shortcuts.xml > "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"
grep -q 'take_readings' "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"
grep -q 'TakeReadingsShortcutActivity' "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"

# No unfinished walkthrough: shortcut must choose an active address and enter the monthly flow
# instead of dropping the user on the settings/history hub.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" || true
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.TakeReadingsShortcutActivity" >/dev/null
PERIOD=$(date +%Y-%m)
wait_text "Home · $PERIOD" 60
wait_text "1 из" 30

# An unfinished session must be resumed without resetting done/skipped or its explicit period.
# Persist a deliberately old period and one completed meter ID, then route again. The activity
# should preserve those values; this complements the pure routing unit test with real prefs I/O.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$GITHUB_WORKSPACE/v20-shortcut.db"
METER_ID=$(python3 - "$GITHUB_WORKSPACE/v20-shortcut.db" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute("select id from meters where status!='closed' or status is null order by rowid limit 1").fetchone()
assert r
print(r[0])
PY
)
ADDRESS_ID=$(python3 - "$GITHUB_WORKSPACE/v20-shortcut.db" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute("select addressId from meters where status!='closed' or status is null order by rowid limit 1").fetchone()
assert r
print(r[0])
PY
)
cat > "$GITHUB_WORKSPACE/v20-walkthrough.xml" <<EOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="address">$ADDRESS_ID</string>
    <string name="period">2026-08</string>
    <set name="done"><string>$METER_ID</string></set>
    <set name="skipped" />
</map>
EOF
adb push "$GITHUB_WORKSPACE/v20-walkthrough.xml" /data/local/tmp/v20-walkthrough.xml >/dev/null
adb shell "run-as $PACKAGE_NAME cp /data/local/tmp/v20-walkthrough.xml shared_prefs/v13_walkthrough.xml"
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.TakeReadingsShortcutActivity" >/dev/null
wait_text "Home · 2026-08" 60
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb exec-out run-as "$PACKAGE_NAME" cat shared_prefs/v13_walkthrough.xml > "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"
grep -q '<string name="period">2026-08</string>' "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"
grep -q "$METER_ID" "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"

echo "v2.0 shortcut regression OK: packaged shortcut opens monthly flow and preserves an unfinished session."
