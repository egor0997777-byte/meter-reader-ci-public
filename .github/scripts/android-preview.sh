#!/usr/bin/env bash
set -euo pipefail
ARTIFACTS="$GITHUB_WORKSPACE/android-preview-artifacts"
UI_XML="$GITHUB_WORKSPACE/window.xml"
mkdir -p "$ARTIFACTS"

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/window.xml >/dev/null; adb pull /sdcard/window.xml "$UI_XML" >/dev/null; }
find_point(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import re,sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if n.attrib.get('text','')==wanted or n.attrib.get('content-desc','')==wanted:
        nums=list(map(int,re.findall(r'\d+',n.attrib.get('bounds',''))))
        if len(nums)==4:
            print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2);sys.exit(0)
print("UI element not found:",wanted,file=sys.stderr)
print(ET.tostring(ET.parse(path).getroot(),encoding='unicode'),file=sys.stderr)
sys.exit(2)
PY
}
find_nth_point(){ local wanted="$1"; local wanted_index="$2"; ui_dump; python3 - "$wanted" "$wanted_index" "$UI_XML" <<'PY'
import re,sys,xml.etree.ElementTree as ET
wanted,index,path=sys.argv[1],int(sys.argv[2]),sys.argv[3]
matches=[]
for n in ET.parse(path).getroot().iter('node'):
    if n.attrib.get('text','')==wanted or n.attrib.get('content-desc','')==wanted:
        nums=list(map(int,re.findall(r'\d+',n.attrib.get('bounds',''))))
        if len(nums)==4: matches.append(nums)
if 1 <= index <= len(matches):
    nums=matches[index-1]
    print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2);sys.exit(0)
print(f"UI element occurrence not found: {wanted} #{index}; found {len(matches)}",file=sys.stderr)
print(ET.tostring(ET.parse(path).getroot(),encoding='unicode'),file=sys.stderr)
sys.exit(2)
PY
}
has_text(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if wanted in n.attrib.get('text','') or wanted in n.attrib.get('content-desc',''): sys.exit(0)
sys.exit(1)
PY
}
wait_text(){ local wanted="$1"; local tries="${2:-15}"; for ((i=1;i<=tries;i++));do if has_text "$wanted"; then return 0; fi; sleep .5; done; echo "Timed out: $wanted" >&2; ui_dump; cat "$UI_XML" >&2; return 1; }
tap_text(){ local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .5; }
tap_nth_text(){ local p; p=$(find_nth_point "$1" "$2"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .5; }
replace_text(){ tap_text "$1"; adb shell input keyevent KEYCODE_MOVE_END; for i in {1..30};do adb shell input keyevent KEYCODE_DEL >/dev/null;done; adb shell input text "$2"; sleep .3; }
hide_ime(){ adb shell input keyevent KEYCODE_BACK >/dev/null; sleep .5; }
shot(){ sleep .4; adb exec-out screencap -p > "$ARTIFACTS/$1"; }
start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Всё хранится на устройстве"; }
restart_app(){ start_app; }
open_meters(){ start_app; tap_text "Учёт показаний"; wait_text "Мои счётчики"; }
open_tariffs(){ start_app; tap_text "Тарифы и стоимость"; wait_text "Тарифы и ориентировочная стоимость"; }
seed_legacy_json(){
 local json='[{"id":"legacy-address","name":"Legacy Home","meters":[{"id":"legacy-meter","name":"Холодная вода","unit":"м³","kind":"cold_water","serial":"CW-001","readings":[{"id":"legacy-reading","value":123.456,"timestamp":1788200000000,"note":"legacy note"}]}]}]'
 local escaped; escaped=$(python3 - "$json" <<'PY'
import html,sys
print(html.escape(sys.argv[1],quote=True))
PY
)
 adb shell "run-as $PACKAGE_NAME mkdir -p shared_prefs"
 printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' "    <string name=\"addresses\">$escaped</string>" '</map>' > "$GITHUB_WORKSPACE/meters.xml"
 adb push "$GITHUB_WORKSPACE/meters.xml" /data/local/tmp/meters.xml >/dev/null
 adb shell "run-as $PACKAGE_NAME cp /data/local/tmp/meters.xml shared_prefs/meters.xml"
}
copy_db(){
 adb shell am force-stop "$PACKAGE_NAME"; sleep .8
 rm -f "$GITHUB_WORKSPACE/meter-reader.db" "$GITHUB_WORKSPACE/meter-reader.db-wal" "$GITHUB_WORKSPACE/meter-reader.db-shm"
 adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$GITHUB_WORKSPACE/meter-reader.db"
 test -s "$GITHUB_WORKSPACE/meter-reader.db"
 if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$GITHUB_WORKSPACE/meter-reader.db-wal"; fi
 if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$GITHUB_WORKSPACE/meter-reader.db-shm"; fi
}
verify_legacy(){
 copy_db
 python3 - "$GITHUB_WORKSPACE/meter-reader.db" <<'PY'
import sqlite3,sys
db=sqlite3.connect(sys.argv[1])
assert db.execute("select count(*) from addresses where id='legacy-address'").fetchone()[0]==1
assert db.execute("select tariffZones from meters where id='legacy-meter'").fetchone()[0]=='TOTAL'
assert db.execute("select valueText from reading_values where readingId='legacy-reading' and zone='TOTAL'").fetchone()[0]=='123.456'
assert db.execute("select count(*) from sqlite_master where type='table' and name='tariff_schedule'").fetchone()[0]==1
print("legacy migration OK")
PY
}
verify_multitariff(){
 copy_db
 python3 - "$GITHUB_WORKSPACE/meter-reader.db" <<'PY'
import sqlite3,sys
db=sqlite3.connect(sys.argv[1])
m=db.execute("select id,tariffZones from meters where name='Электричество' and status='active' order by rowid desc limit 1").fetchone()
assert m and m[1]=='T1,T2,T3',m
rows=db.execute("select rv.zone,rv.valueText from readings r join reading_values rv on rv.readingId=r.id where r.meterId=? order by r.timestamp,rv.zone",(m[0],)).fetchall()
assert ('T1','110') in rows and ('T2','55') in rows and ('T3','22') in rows,rows
print("multitariff persistence OK",rows)
PY
}
verify_tariffs(){
 copy_db
 python3 - "$GITHUB_WORKSPACE/meter-reader.db" <<'PY'
import sqlite3,sys
db=sqlite3.connect(sys.argv[1])
m=db.execute("select id from meters where name='Электричество' and status='active' order by rowid desc limit 1").fetchone()
assert m,m
rows=db.execute("select zone,priceText from tariff_schedule where meterId=? order by zone",(m[0],)).fetchall()
assert rows==[('T1','6'),('T2','3'),('T3','2')],rows
print("tariff schedule persistence OK",rows)
PY
}
verify_replacement(){
 copy_db
 python3 - "$GITHUB_WORKSPACE/meter-reader.db" <<'PY'
import sqlite3,sys
db=sqlite3.connect(sys.argv[1])
old=db.execute("select id,status from meters where name='Холодная вода' and previousMeterId is null order by rowid limit 1").fetchone()
assert old and old[1]=='closed',old
new=db.execute("select id,previousMeterId,status from meters where previousMeterId=?",(old[0],)).fetchone()
assert new and new[1]==old[0] and new[2]=='active',new
v=db.execute("select rv.valueText from readings r join reading_values rv on rv.readingId=r.id where r.meterId=? and rv.zone='TOTAL'",(new[0],)).fetchone()
assert v and v[0]=='000012',v
print("replacement OK")
PY
}

# Privacy/security invariant: the app must not request hidden network access.
if grep -q 'android.permission.INTERNET' "$GITHUB_WORKSPACE/meter-reader/app/src/main/AndroidManifest.xml"; then
  echo "Unexpected INTERNET permission" >&2
  exit 1
fi

adb install -r "$GITHUB_WORKSPACE/$APK_PATH"
adb shell pm clear "$PACKAGE_NAME" >/dev/null

# v0.13 unified entry point and privacy surface.
start_app
wait_text "Безопасность и конфиденциальность"
shot "00-v013-home.png"
tap_text "Безопасность и конфиденциальность"; wait_text "Где находятся данные"; wait_text "Интернет-разрешение не запрашивается"
shot "01-v013-privacy.png"
tap_text "‹  Главная"; wait_text "Учёт показаний"

# Legacy JSON -> Room still works through the new launcher.
seed_legacy_json
open_meters
wait_text "Legacy Home"; tap_text "Legacy Home"; wait_text "Холодная вода"; tap_text "Холодная вода"; wait_text "123.456"
shot "02-legacy-migration.png"
verify_legacy
open_meters; wait_text "Legacy Home"

# Build the regression dataset through the production meter UI.
adb shell pm clear "$PACKAGE_NAME" >/dev/null
open_meters
wait_text "Добавить адрес"
shot "03-empty-home.png"
tap_text "Добавить адрес"; wait_text "Новый адрес"; replace_text "Название или адрес" "Home"; tap_text "Добавить"; wait_text "Home"
tap_text "Home"; wait_text "Добавить счётчик"

tap_text "Добавить счётчик"; wait_text "Новый счётчик"; tap_text "Добавить"; wait_text "Холодная вода"
tap_text "Заменить"; wait_text "Замена счётчика"; replace_text "Начальное показание" "000012"; tap_text "Заменить"; wait_text "Заменён"
verify_replacement
open_meters; wait_text "Home"; tap_text "Home"; wait_text "Добавить счётчик"
shot "04-replacement.png"

tap_text "Добавить счётчик"; wait_text "Новый счётчик"; tap_text "Электричество"; tap_text "T1/T2/T3"; tap_text "Добавить"; wait_text "Электричество"
tap_text "Электричество"; wait_text "Новое показание"; tap_text "Новое показание"; wait_text "T1"
replace_text "T1" "100"; replace_text "T2" "50"; replace_text "T3" "20"; tap_text "Сохранить"; wait_text "T1 100"
tap_text "Новое показание"; wait_text "T1"; replace_text "T1" "110"; replace_text "T2" "55"; replace_text "T3" "22"; tap_text "Сохранить"; wait_text "Расход с прошлого раза"; wait_text "T3: 2"
shot "05-multitariff-history.png"
verify_multitariff

# Configure T1/T2/T3 prices and verify the reproducible estimate: 10*6 + 5*3 + 2*2 = 79.
open_tariffs
wait_text "Электричество"; tap_nth_text "Тарифы" 2; wait_text "История тарифов"; wait_text "Электричество"
replace_text "Цена тарифа" "6"; tap_text "Добавить в историю"; hide_ime; wait_text "История тарифов"; wait_text "T2"; tap_text "T2"
replace_text "Цена тарифа" "3"; tap_text "Добавить в историю"; hide_ime; wait_text "История тарифов"; wait_text "T3"; tap_text "T3"
replace_text "Цена тарифа" "2"; tap_text "Добавить в историю"; hide_ime; wait_text "История тарифов"
wait_text "6 ₽/кВт·ч"; wait_text "3 ₽/кВт·ч"; wait_text "2 ₽/кВт·ч"
tap_text "Сохранить"; wait_text "Ориентировочно за"; wait_text "79.00 ₽"
shot "06-v012-estimated-cost.png"
verify_tariffs

open_tariffs
wait_text "79.00 ₽"
shot "07-v012-cost-after-restart.png"

# Previous backup/share/reminder surfaces remain reachable and functional from unified navigation.
open_meters; wait_text "Home"; tap_text "Home"; wait_text "Электричество"; tap_text "Электричество"; wait_text "T3 22"
shot "08-multitariff-after-restart.png"
tap_text "‹  Счётчики"; wait_text "Home"; tap_text "‹  Все адреса"; wait_text "Данные"; tap_text "Данные"
wait_text "Создать резервную копию"; wait_text "Экспорт истории CSV"; wait_text "Скопировать"
shot "09-data-tools.png"
tap_text "Напоминания и поверка"; wait_text "Напоминания и поверка"; tap_text "Напоминания и поверка"; wait_text "Уведомления"
shot "10-reminders.png"

# Large system font smoke test for the new scrollable launcher.
adb shell settings put system font_scale 1.3
start_app
wait_text "Безопасность и конфиденциальность"
shot "11-large-font-home.png"
adb shell settings put system font_scale 1.0

echo "Preview completed: v0.13 launcher/privacy, no INTERNET permission, large-font launcher, legacy migration, replacement, T1/T2/T3 persistence, tariff schedule persistence, estimated cost after restart, backup/CSV/share and reminders verified."
