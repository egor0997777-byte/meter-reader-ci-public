#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v20-shortcut-window.xml"
DB_FILE="$GITHUB_WORKSPACE/v20-shortcut.db"

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

# The preceding monthly-flow regression leaves a real Home dataset in Room.
# Verify the static launcher shortcut is packaged and points at the dedicated routing activity.
AAPT=$(find "${ANDROID_HOME:?}/build-tools" -type f -name aapt -perm -111 | sort -V | tail -n 1)
"$AAPT" dump xmltree "$GITHUB_WORKSPACE/$APK_PATH" res/xml/shortcuts.xml > "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"
grep -q 'take_readings' "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"
grep -q 'TakeReadingsShortcutActivity' "$GITHUB_WORKSPACE/v20-shortcuts-xml.txt"

PERIOD=$(date +%Y-%m)
copy_db
readarray -t ROUTE < <(python3 - "$DB_FILE" "$PERIOD" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1]); period=sys.argv[2]
# The current regression dataset uses explicit billingPeriod. Find the first address that still
# has at least one active meter without a reading in this period, matching production ordering.
row=c.execute('''
select a.id,a.name
from addresses a
where exists (
  select 1 from meters m
  where m.addressId=a.id and m.status!='closed'
    and not exists (
      select 1 from readings r where r.meterId=m.id and r.billingPeriod=?
    )
)
order by a.rowid
limit 1
''',(period,)).fetchone()
if row:
 print(row[0]); print(row[1])
PY
)
PENDING_ADDRESS_ID="${ROUTE[0]:-}"
PENDING_ADDRESS_NAME="${ROUTE[1]:-}"

# With no unfinished session the shortcut either enters the first genuinely pending address,
# or — when the current period is already complete — safely returns to the period-status hub.
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" || true
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.TakeReadingsShortcutActivity" >/dev/null
if [[ -n "$PENDING_ADDRESS_ID" ]]; then
  wait_text "$PENDING_ADDRESS_NAME · $PERIOD" 60
else
  wait_text "Снять → проверить → передать" 60
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  if adb shell "run-as $PACKAGE_NAME test -f shared_prefs/v13_walkthrough.xml"; then
    adb exec-out run-as "$PACKAGE_NAME" cat shared_prefs/v13_walkthrough.xml > "$GITHUB_WORKSPACE/v20-no-pending-session.xml"
    if grep -q '<string name="address">' "$GITHUB_WORKSPACE/v20-no-pending-session.xml"; then
      echo 'Completed period shortcut unexpectedly created a new walkthrough session' >&2
      cat "$GITHUB_WORKSPACE/v20-no-pending-session.xml" >&2
      exit 1
    fi
  fi
fi

# An unfinished session must be resumed without resetting done/skipped or its explicit period.
# Persist a deliberately old period and one completed meter ID, then route again.
copy_db
METER_ID=$(python3 - "$DB_FILE" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute("select id from meters where status!='closed' order by rowid limit 1").fetchone()
assert r
print(r[0])
PY
)
ADDRESS_ID=$(python3 - "$DB_FILE" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute("select addressId from meters where status!='closed' order by rowid limit 1").fetchone()
assert r
print(r[0])
PY
)
ADDRESS_NAME=$(python3 - "$DB_FILE" "$ADDRESS_ID" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
r=c.execute('select name from addresses where id=?',(sys.argv[2],)).fetchone()
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
wait_text "$ADDRESS_NAME · 2026-08" 60
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb exec-out run-as "$PACKAGE_NAME" cat shared_prefs/v13_walkthrough.xml > "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"
grep -q '<string name="period">2026-08</string>' "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"
grep -q "$METER_ID" "$GITHUB_WORKSPACE/v20-walkthrough-after.xml"

# This fixture is deliberately synthetic and must not leak into later regressions.
# Restore the normal launcher state before the backup/restore and app-lock suites run.
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" || true
adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
wait_text "Снять → проверить → передать" 60

echo "v2.0 shortcut regression OK: packaged shortcut is period-aware and preserves an unfinished explicit-period session."
