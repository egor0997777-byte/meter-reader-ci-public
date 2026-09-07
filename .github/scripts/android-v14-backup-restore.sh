#!/usr/bin/env bash
set -euo pipefail

ARTIFACTS="$GITHUB_WORKSPACE/android-preview-artifacts"
UI_XML="$GITHUB_WORKSPACE/v14-window.xml"
BACKUP_HOST="$GITHUB_WORKSPACE/v14-clean-device-backup.zip"
ARCHIVE_DB="$GITHUB_WORKSPACE/v14-archive-room.db"
EXPECTED_CONTENT="$GITHUB_WORKSPACE/v14-expected-content.json"
mkdir -p "$ARTIFACTS"

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/v14-window.xml >/dev/null; adb pull /sdcard/v14-window.xml "$UI_XML" >/dev/null; }
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
print(f"UI element not found: {wanted}",file=sys.stderr);sys.exit(2)
PY
}
tap_text(){ local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .7; }
shot(){ sleep .4; adb exec-out screencap -p > "$ARTIFACTS/$1"; }
start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Снять → проверить → передать"; }
open_data_tools(){
  start_app
  tap_text "Учёт и история"; wait_text "Мои счётчики"
  if has_text "Данные"; then tap_text "Данные"; else
    adb shell input keyevent KEYCODE_BACK >/dev/null; sleep .5; wait_text "Учёт и история"; tap_text "Учёт и история"; wait_text "Мои счётчики"; tap_text "Данные"
  fi
  wait_text "Создать резервную копию"
}
copy_db(){
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null; sleep .5
  rm -f "$GITHUB_WORKSPACE/v14.db" "$GITHUB_WORKSPACE/v14.db-wal" "$GITHUB_WORKSPACE/v14.db-shm"
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$GITHUB_WORKSPACE/v14.db"
  test -s "$GITHUB_WORKSPACE/v14.db"
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$GITHUB_WORKSPACE/v14.db-wal"; fi
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$GITHUB_WORKSPACE/v14.db-shm"; fi
}
create_generation(){
  open_data_tools
  tap_text "Создать резервную копию"
  sleep 1
  adb shell input keyevent KEYCODE_BACK >/dev/null
  sleep .7
}

# Existing regression flow has populated a representative dataset. Create a real v4 backup
# through the production UI; local generation is committed before the SAF save dialog opens.
open_data_tools
shot "18-v14-data-tools.png"
tap_text "Создать резервную копию"
sleep 1
adb shell input keyevent KEYCODE_BACK >/dev/null
sleep .7

# Exercise rotation, not just the minimum count. Five production backup actions must retain exactly three.
create_generation
create_generation
create_generation
create_generation
generation_count=$(adb shell "run-as $PACKAGE_NAME sh -c 'ls -1 files/backup-generations/backup-*.zip 2>/dev/null | wc -l'" | tr -d '\r ')
if [ "${generation_count:-0}" -ne 3 ]; then echo "Expected exactly 3 retained local backup generations after rotation, got ${generation_count:-0}" >&2; exit 1; fi
last_successful=$(adb exec-out run-as "$PACKAGE_NAME" cat files/backup-generations/last-successful.txt | tr -d '\r\n')
if [ -z "$last_successful" ]; then echo "last-successful.txt is empty" >&2; exit 1; fi
adb shell "run-as $PACKAGE_NAME test -s files/backup-generations/$last_successful"

backup_path=$(adb shell "run-as $PACKAGE_NAME sh -c 'ls -1t files/backup-generations/backup-*.zip 2>/dev/null | head -n 1'" | tr -d '\r')
if [ -z "$backup_path" ]; then echo "No local v1.4 backup generation created" >&2; exit 1; fi
adb exec-out run-as "$PACKAGE_NAME" cat "$backup_path" > "$BACKUP_HOST"
test -s "$BACKUP_HOST"

python3 - "$BACKUP_HOST" "$ARCHIVE_DB" <<'PY'
import hashlib,json,sqlite3,sys,zipfile
p,db_out=sys.argv[1],sys.argv[2]
with zipfile.ZipFile(p) as z:
    names=z.namelist()
    assert names.count('manifest.json')==1,names
    manifest=json.loads(z.read('manifest.json'))
    assert manifest['format']=='moi-schetschiki-backup',manifest
    assert manifest['version']==4,manifest
    declared={e['path']:e for e in manifest['entries']}
    actual=set(names)-{'manifest.json'}
    assert set(declared)==actual,(declared.keys(),actual)
    assert 'data.json' in actual,actual
    assert 'database/meter-reader.db' in actual,actual
    for name,item in declared.items():
        data=z.read(name)
        assert len(data)==item['size'],name
        assert hashlib.sha256(data).hexdigest()==item['sha256'].lower(),name
    payload=json.loads(z.read('data.json'))
    assert payload['format']=='moi-schetschiki-backup' and payload['version']==4
    assert payload.get('addresses'),payload
    assert payload.get('metadata',{}).get('version')==1,payload.get('metadata')
    db=z.read('database/meter-reader.db')
    assert db[:16]==b'SQLite format 3\x00'
    open(db_out,'wb').write(db)

c=sqlite3.connect(db_out)
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
tables={r[0] for r in c.execute("select name from sqlite_master where type='table'")}
required={'addresses','metering_points','meters','readings','reading_values','tariff_schedule','submissions','submission_items'}
assert required <= tables,(required-tables,tables)
assert c.execute("select count(*) from room_master_table").fetchone()[0]>=1
c.close()
print('v1.4 backup manifest/checksums + Room DB snapshot + metadata OK')
PY

# Preserve exact logical content from the source installation, not only row counts.
copy_db
python3 - "$GITHUB_WORKSPACE/v14.db" "$EXPECTED_CONTENT" <<'PY'
import json,sqlite3,sys
c=sqlite3.connect(sys.argv[1])
tables=('addresses','metering_points','meters','readings','reading_values','tariff_schedule','submissions','submission_items')
out={}
for table in tables:
    cols=[r[1] for r in c.execute(f'pragma table_info({table})')]
    rows=[dict(zip(cols,row)) for row in c.execute(f'select * from {table}')]
    out[table]=sorted(rows,key=lambda row: json.dumps(row,sort_keys=True,ensure_ascii=False))
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
json.dump(out,open(sys.argv[2],'w'),ensure_ascii=False,sort_keys=True,indent=2)
print('captured exact source content', {k:len(v) for k,v in out.items()})
PY

python3 - "$ARCHIVE_DB" "$EXPECTED_CONTENT" <<'PY'
import json,sqlite3,sys
c=sqlite3.connect(sys.argv[1]); expected=json.load(open(sys.argv[2]))
for table,want in expected.items():
    cols=[r[1] for r in c.execute(f'pragma table_info({table})')]
    got=[dict(zip(cols,row)) for row in c.execute(f'select * from {table}')]
    got=sorted(got,key=lambda row: json.dumps(row,sort_keys=True,ensure_ascii=False))
    assert got==want,(table,want,got)
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
print('v1.4 archived Room DB matches exact source content')
PY

# Simulate a clean phone/app installation. The exported archive is outside app storage.
adb push "$BACKUP_HOST" /sdcard/Download/moi-schetschiki-backup.zip >/dev/null
adb shell pm clear "$PACKAGE_NAME" >/dev/null
open_data_tools
shot "19-v14-clean-install-data-tools.png"
tap_text "Восстановить из копии"
# DocumentsUI may open on an empty Recent view even though the file is present in Download.
# Navigate explicitly to Downloads instead of treating Recent indexing as an app failure.
if ! has_text "moi-schetschiki-backup.zip"; then
  wait_text "Show roots" 20
  tap_text "Show roots"
  wait_text "Downloads" 20
  tap_text "Downloads"
fi
wait_text "moi-schetschiki-backup.zip" 40
tap_text "moi-schetschiki-backup.zip"
wait_text "Восстановить резервную копию?" 30
shot "20-v14-restore-confirm.png"
tap_text "Восстановить"
wait_text "Резервная копия восстановлена" 30
shot "21-v14-restore-success.png"
tap_text "OK"
wait_text "Добавить адрес" 30
shot "22-v14-restored.png"
sleep .5

copy_db
python3 - "$GITHUB_WORKSPACE/v14.db" "$EXPECTED_CONTENT" <<'PY'
import json,sqlite3,sys
c=sqlite3.connect(sys.argv[1]); expected=json.load(open(sys.argv[2]))
for table,want in expected.items():
    cols=[r[1] for r in c.execute(f'pragma table_info({table})')]
    got=[dict(zip(cols,row)) for row in c.execute(f'select * from {table}')]
    got=sorted(got,key=lambda row: json.dumps(row,sort_keys=True,ensure_ascii=False))
    assert got==want,(table,want,got)
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
assert c.execute("select count(*) from readings r left join meters m on m.id=r.meterId where m.id is null").fetchone()[0]==0
assert c.execute("select count(*) from submission_items i left join submissions s on s.id=i.submissionId where s.id is null").fetchone()[0]==0
print('v1.4 clean-install restore exact content/FK/integrity OK')
PY

start_app
wait_text "Снять → проверить → передать"
shot "23-v14-home-after-clean-restore.png"

echo "v1.4 backup/restore regression OK: manifest/checksum + Room DB validation, metadata presence, exact three-generation rotation, exact-content clean-install restore, FK and integrity verified."
