#!/usr/bin/env bash
set -euo pipefail

ARTIFACTS="$GITHUB_WORKSPACE/android-preview-artifacts"
UI_XML="$GITHUB_WORKSPACE/window-v11.xml"
mkdir -p "$ARTIFACTS"

ui_dump(){
  adb shell uiautomator dump --compressed /sdcard/window-v11.xml >/dev/null
  adb pull /sdcard/window-v11.xml "$UI_XML" >/dev/null
}

has_text(){
  local wanted="$1"
  ui_dump
  python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if wanted in n.attrib.get('text','') or wanted in n.attrib.get('content-desc',''):
        sys.exit(0)
sys.exit(1)
PY
}

wait_text(){
  local wanted="$1"; local tries="${2:-20}"
  for ((i=1;i<=tries;i++)); do
    if has_text "$wanted"; then return 0; fi
    sleep .5
  done
  echo "Timed out waiting for: $wanted" >&2
  ui_dump
  cat "$UI_XML" >&2
  return 1
}

find_point(){
  local wanted="$1"
  ui_dump
  python3 - "$wanted" "$UI_XML" <<'PY'
import re,sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if n.attrib.get('text','') == wanted or n.attrib.get('content-desc','') == wanted:
        nums=list(map(int,re.findall(r'\d+',n.attrib.get('bounds',''))))
        if len(nums)==4:
            print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2)
            sys.exit(0)
print(f"UI element not found: {wanted}",file=sys.stderr)
sys.exit(2)
PY
}

tap_text(){
  local p x y
  p=$(find_point "$1")
  read -r x y <<<"$p"
  adb shell input tap "$x" "$y"
  sleep .5
}

replace_text(){
  tap_text "$1"
  adb shell input keyevent KEYCODE_MOVE_END
  for _ in {1..30}; do adb shell input keyevent KEYCODE_DEL >/dev/null; done
  adb shell input text "$2"
  sleep .3
}

hide_ime(){ adb shell input keyevent KEYCODE_BACK >/dev/null; sleep .4; }
scroll_down(){ adb shell input swipe 540 1750 540 650 450 >/dev/null; sleep .5; }
scroll_until(){
  local wanted="$1"
  for _ in {1..6}; do
    if has_text "$wanted"; then return 0; fi
    scroll_down
  done
  wait_text "$wanted" 2
}
shot(){ sleep .4; adb exec-out screencap -p > "$ARTIFACTS/$1"; }

start_v13(){
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
  sleep .6
}

copy_db(){
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  sleep .8
  rm -f "$GITHUB_WORKSPACE/meter-reader-v11.db" "$GITHUB_WORKSPACE/meter-reader-v11.db-wal" "$GITHUB_WORKSPACE/meter-reader-v11.db-shm"
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$GITHUB_WORKSPACE/meter-reader-v11.db"
  test -s "$GITHUB_WORKSPACE/meter-reader-v11.db"
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$GITHUB_WORKSPACE/meter-reader-v11.db-wal"
  fi
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$GITHUB_WORKSPACE/meter-reader-v11.db-shm"
  fi
}

# Validate the current v1.3 launcher and monthly walkthrough against the same persisted dataset.
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
adb shell "run-as $PACKAGE_NAME rm -f shared_prefs/v13_walkthrough.xml" >/dev/null 2>&1 || true
start_v13
wait_text "Мои счётчики"
wait_text "Снять показания"
shot "12-v13-home.png"

tap_text "Снять показания"
wait_text "Холодная вода"
wait_text "Новое показание"
shot "13-v13-walk-first-meter.png"
replace_text "Новое показание" "000013"
hide_ime
scroll_until "Где стоит счётчик"
replace_text "Где стоит счётчик" "bathroom"
hide_ime
scroll_until "Заметка к периоду · необязательно"
replace_text "Заметка к периоду · необязательно" "v13-note"
hide_ime
scroll_until "Сохранить и дальше"
tap_text "Сохранить и дальше"
wait_text "Электричество"

# A process restart in the middle of the walk must resume at the next unfinished meter.
start_v13
wait_text "Электричество"
wait_text "2 из 2"
shot "14-v13-resume-after-restart.png"

# Finish the multi-tariff meter instead of skipping it so the real submission flow is testable.
replace_text "T1 · новое показание" "120"
hide_ime
scroll_until "T2 · новое показание"
replace_text "T2 · новое показание" "65"
hide_ime
scroll_until "T3 · новое показание"
replace_text "T3 · новое показание" "30"
hide_ime
scroll_until "Сохранить и дальше"
tap_text "Сохранить и дальше"
wait_text "Показания готовы"
wait_text "Отметить переданным"
shot "15-v13-summary.png"

# v1.3 is not validated until the UI actually records a Submission snapshot.
# The durable post-submit UI state is "Уже передано"; do not depend on a transient snackbar/toast.
tap_text "Отметить переданным"
wait_text "Уже передано"
shot "15b-v13-submitted-summary.png"
tap_text "Готово"
wait_text "Мои счётчики"
wait_text "Передано"
shot "16-v13-completed-home.png"

copy_db
python3 - "$GITHUB_WORKSPACE/meter-reader-v11.db" <<'PY'
import sqlite3,sys
p=sys.argv[1]
db=sqlite3.connect(p)
m=db.execute("select id,location from meters where name='Холодная вода' and status='active' order by rowid desc limit 1").fetchone()
assert m, 'active cold-water meter missing'
assert m[1]=='bathroom', m
r=db.execute("select r.note,rv.valueText from readings r join reading_values rv on rv.readingId=r.id where r.meterId=? and rv.zone='TOTAL' order by r.timestamp desc limit 1",(m[0],)).fetchone()
assert r==('v13-note','000013'), r
sub=db.execute("select id,billingPeriod,snapshotText from submissions order by submittedAt desc limit 1").fetchone()
assert sub, 'v1.3 UI did not persist a submission'
assert sub[1], sub
assert 'Холодная вода' in sub[2] and 'Электричество' in sub[2], sub
items=db.execute("select zone,valueText from submission_items where submissionId=? order by zone",(sub[0],)).fetchall()
assert ('TOTAL','000013') in items, items
assert ('T1','120') in items and ('T2','65') in items and ('T3','30') in items, items
print('v1.3 walkthrough/submission persistence OK',m,r,sub,items)
PY

# The actual v1.3 launcher must remain usable at large font.
adb shell settings put system font_scale 1.3
start_v13
wait_text "Мои счётчики"
wait_text "Снять показания"
shot "17-v13-large-font-home.png"
adb shell settings put system font_scale 1.0

echo "v1.3 walkthrough OK: launcher, sequential entry, restart resume, multi-tariff entry, summary, Submission persistence, completed status and large-font hub verified."
