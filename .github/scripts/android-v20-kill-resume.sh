#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v20-kill-resume-window.xml"
DB_FILE="$GITHUB_WORKSPACE/v20-kill-resume.db"
PREFS_FILE="$GITHUB_WORKSPACE/v20-kill-resume-prefs.xml"

ui_dump(){
  adb shell uiautomator dump --compressed /sdcard/v20-kill-resume-window.xml >/dev/null
  adb pull /sdcard/v20-kill-resume-window.xml "$UI_XML" >/dev/null
}

wait_text(){
  local wanted="$1" tries="${2:-60}"
  for ((i=1;i<=tries;i++)); do
    ui_dump
    if python3 - "$wanted" "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
wanted, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).getroot().iter('node'):
    if wanted in node.attrib.get('text','') or wanted in node.attrib.get('content-desc',''):
        raise SystemExit(0)
raise SystemExit(1)
PY
    then return 0; fi
    sleep .5
  done
  echo "Timed out waiting for UI text: $wanted" >&2
  cat "$UI_XML" >&2
  return 1
}

tap_text(){
  local wanted="$1"
  ui_dump
  read -r X Y < <(python3 - "$wanted" "$UI_XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
wanted, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).getroot().iter('node'):
    text = node.attrib.get('text','') + ' ' + node.attrib.get('content-desc','')
    if wanted in text:
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
        if m:
            x1,y1,x2,y2 = map(int,m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            raise SystemExit(0)
raise SystemExit(1)
PY
  )
  adb shell input tap "$X" "$Y" >/dev/null
}

copy_db(){
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  rm -f "$DB_FILE" "$DB_FILE-wal" "$DB_FILE-shm"
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$DB_FILE"
  test -s "$DB_FILE"
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$DB_FILE-wal"
  fi
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$DB_FILE-shm"
  fi
}

# Start from a clean walkthrough session while keeping the Room fixture prepared by earlier regressions.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" || true
adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
wait_text "Снять → проверить → передать"

# Enter the real monthly flow. The fixture should have at least one active point; if the period is
# already complete the hub offers the summary action instead, which is not a valid kill-mid-flow fixture.
ui_dump
if ! grep -q 'Снять показания' "$UI_XML"; then
  echo 'v2.0 kill/resume regression requires a pending monthly reading fixture' >&2
  cat "$UI_XML" >&2
  exit 1
fi
tap_text "Снять показания"
wait_text "Пропустить в этом периоде"

# Exercise the real skip action, then kill the process immediately after the UI advances.
tap_text "Пропустить в этом периоде"
sleep 0.10
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb exec-out run-as "$PACKAGE_NAME" cat shared_prefs/v13_walkthrough.xml > "$PREFS_FILE"
python3 - "$PREFS_FILE" <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
strings={n.attrib.get('name'): (n.text or '') for n in root.findall('string')}
skipped=root.find("set[@name='skipped']")
assert strings.get('address'), 'walkthrough address was lost after process kill'
assert strings.get('period'), 'walkthrough period was lost after process kill'
assert skipped is not None and len(skipped.findall('string')) >= 1, 'skipped point was lost after process kill'
print(strings['address'])
print(strings['period'])
PY
readarray -t SESSION < <(python3 - "$PREFS_FILE" <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
strings={n.attrib.get('name'): (n.text or '') for n in root.findall('string')}
print(strings['address']); print(strings['period'])
PY
)
ADDRESS_ID="${SESSION[0]}"
PERIOD="${SESSION[1]}"
copy_db
ADDRESS_NAME=$(python3 - "$DB_FILE" "$ADDRESS_ID" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute('select name from addresses where id=?',(sys.argv[2],)).fetchone()
assert r
print(r[0])
PY
)

# A cold restart must reopen the same explicit-period session instead of starting a fresh current-period walk.
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
wait_text "$ADDRESS_NAME · $PERIOD"
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb exec-out run-as "$PACKAGE_NAME" cat shared_prefs/v13_walkthrough.xml > "$GITHUB_WORKSPACE/v20-kill-resume-after.xml"
cmp -s "$PREFS_FILE" "$GITHUB_WORKSPACE/v20-kill-resume-after.xml" || {
  echo 'Walkthrough state changed across a cold restart without user action' >&2
  diff -u "$PREFS_FILE" "$GITHUB_WORKSPACE/v20-kill-resume-after.xml" || true
  exit 1
}

# Do not leak the synthetic partial/skipped session into backup/app-lock regressions.
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" || true
adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
wait_text "Снять → проверить → передать"

echo 'v2.0 kill/resume regression OK: explicit period and skipped-point progress survive a process kill and cold restart.'
