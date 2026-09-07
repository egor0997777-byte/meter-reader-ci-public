#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v14-fault-window.xml"
BASE_BACKUP="$GITHUB_WORKSPACE/v14-clean-device-backup.zip"
PHOTO_BACKUP="$GITHUB_WORKSPACE/v14-photo-A.zip"
FAILED_BACKUP="$GITHUB_WORKSPACE/v14-failed-B.zip"
DB_COPY="$GITHUB_WORKSPACE/v14-fault.db"

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/v14-fault-window.xml >/dev/null; adb pull /sdcard/v14-fault-window.xml "$UI_XML" >/dev/null; }
has_text(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if wanted in n.attrib.get('text','') or wanted in n.attrib.get('content-desc',''): sys.exit(0)
sys.exit(1)
PY
}
wait_text(){ local wanted="$1"; local tries="${2:-30}"; for ((i=1;i<=tries;i++)); do if has_text "$wanted"; then return 0; fi; sleep .5; done; echo "Timed out: $wanted" >&2; ui_dump; cat "$UI_XML" >&2; return 1; }
find_point(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import re,sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if n.attrib.get('text','')==wanted or n.attrib.get('content-desc','')==wanted:
        nums=list(map(int,re.findall(r'\d+',n.attrib.get('bounds',''))))
        if len(nums)==4:
            print((nums[0]+nums[2])//2,(nums[1]+nums[3])//2);sys.exit(0)
raise SystemExit(f'UI element not found: {wanted}')
PY
}
tap_text(){ local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .7; }
start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Снять → проверить → передать"; }
open_data_tools(){ start_app; tap_text "Учёт и история"; wait_text "Мои счётчики"; tap_text "Данные"; wait_text "Восстановить из копии"; }
select_download_file(){
  local file="$1"
  if ! has_text "$file"; then
    wait_text "Show roots" 20
    tap_text "Show roots"
    wait_text "Downloads" 20
    tap_text "Downloads"
  fi
  wait_text "$file" 40
  tap_text "$file"
}
restore_valid(){
  local file="$1"
  open_data_tools
  tap_text "Восстановить из копии"
  select_download_file "$file"
  wait_text "Восстановить резервную копию?" 30
  tap_text "Восстановить"
  wait_text "Данные восстановлены" 30
  tap_text "OK"
  sleep .7
}
copy_db(){
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null; sleep .5
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$DB_COPY"
  test -s "$DB_COPY"
}

# The normal v1.4 regression creates this real Room-backed archive immediately before us.
test -s "$BASE_BACKUP"

# Build restore A from the proven archive, adding one real photo while preserving the Room snapshot.
python3 - "$BASE_BACKUP" "$PHOTO_BACKUP" <<'PY'
import base64,hashlib,json,sys,zipfile
src,dst=sys.argv[1],sys.argv[2]
with zipfile.ZipFile(src) as z:
    files={n:z.read(n) for n in z.namelist() if n!='manifest.json'}
    old_manifest=json.loads(z.read('manifest.json'))
payload=json.loads(files['data.json'])
reading=None
for a in payload['addresses']:
    for m in a.get('meters',[]):
        if m.get('readings'):
            reading=m['readings'][0]; break
    if reading: break
assert reading is not None
reading['hasPhoto']=True
files['data.json']=json.dumps(payload,ensure_ascii=False,indent=2).encode()
# Minimal valid JPEG, content itself is irrelevant to restore integrity but exercises real file staging.
jpeg=base64.b64decode('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9k=')
files[f"photos/{reading['id']}.jpg"]=jpeg
entries=[]
for path,data in files.items():
    entries.append({'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
manifest={'format':'moi-schetschiki-backup','version':4,'createdAt':old_manifest.get('createdAt',0),'entries':entries}
with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode())
    for path,data in files.items(): z.writestr(path,data)
print(reading['id'])
PY

printf 'this is deliberately not a valid backup' > "$FAILED_BACKUP"
adb push "$PHOTO_BACKUP" /sdcard/Download/v14-photo-A.zip >/dev/null
adb push "$FAILED_BACKUP" /sdcard/Download/v14-failed-B.zip >/dev/null

restore_valid "v14-photo-A.zip"
copy_db
photo_uri=$(python3 - "$DB_COPY" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
row=c.execute("select photoUri from readings where photoUri is not null order by timestamp limit 1").fetchone()
assert row and row[0].startswith('content://ru.egor.meters.fileprovider/'),row
assert 'restore_staging_' in row[0],row
print(row[0])
PY
)
photo_rel=${photo_uri#*meter_photos/}
photo_path="/sdcard/Android/data/$PACKAGE_NAME/files/Pictures/$photo_rel"
adb shell test -s "$photo_path"
# Simulate restore A being older than one hour; later failures must not clean it up by age.
adb shell "touch -t 202609072000.00 '${photo_path%/*}'" || true

# Capture exact committed A state before attempting B.
copy_db
cp "$DB_COPY" "$GITHUB_WORKSPACE/v14-A-before-failed-B.db"

# Restore B must fail during archive parsing, before confirmation/commit.
open_data_tools
tap_text "Восстановить из копии"
select_download_file "v14-failed-B.zip"
wait_text "Пустой файл резервной копии" 3 || wait_text "В архиве нет manifest.json" 3 || wait_text "Мои счётчики" 20
# Dismiss the controlled error dialog if present.
if has_text "OK"; then tap_text "OK"; fi
sleep .7

copy_db
python3 - "$GITHUB_WORKSPACE/v14-A-before-failed-B.db" "$DB_COPY" <<'PY'
import sqlite3,sys,json
def logical(path):
    c=sqlite3.connect(path)
    out={}
    for t in ('addresses','metering_points','meters','readings','reading_values','tariff_schedule','submissions','submission_items'):
        cols=[r[1] for r in c.execute(f'pragma table_info({t})')]
        rows=[dict(zip(cols,r)) for r in c.execute(f'select * from {t}')]
        out[t]=sorted(rows,key=lambda x:json.dumps(x,sort_keys=True,ensure_ascii=False))
    assert c.execute('pragma foreign_key_check').fetchall()==[]
    assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
    return out
assert logical(sys.argv[1])==logical(sys.argv[2]),'failed restore B changed live Room data'
print('restore A -> failed B preserved exact Room dataset')
PY
adb shell test -s "$photo_path"

echo "v1.4 failed-restore regression OK: committed restore-A photo survives an older-than-one-hour failed restore B and Room data remains exact."
