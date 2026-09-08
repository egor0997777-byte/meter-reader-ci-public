#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v14-kill-window.xml"
BASE_BACKUP="$GITHUB_WORKSPACE/v14-clean-device-backup.zip"
KILL_BACKUP="$GITHUB_WORKSPACE/v14-kill-target.zip"
OVERSIZE_BACKUP="$GITHUB_WORKSPACE/v14-oversized-entry.zip"
DB_BASE="$GITHUB_WORKSPACE/v14-kill-base.db"
DB_AFTER_OVERSIZE="$GITHUB_WORKSPACE/v14-after-oversize.db"
DB_AFTER_PREKILL="$GITHUB_WORKSPACE/v14-after-precommit-kill.db"
DB_AFTER_POSTKILL="$GITHUB_WORKSPACE/v14-after-postcommit-kill.db"
NAME_FILE="$GITHUB_WORKSPACE/v14-kill-names.txt"
APP_PICTURES="/storage/emulated/0/Android/data/$PACKAGE_NAME/files/Pictures"

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/v14-kill-window.xml >/dev/null; adb pull /sdcard/v14-kill-window.xml "$UI_XML" >/dev/null; }
has_text(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if wanted in n.attrib.get('text','') or wanted in n.attrib.get('content-desc',''): raise SystemExit(0)
raise SystemExit(1)
PY
}
wait_text(){ local wanted="$1"; local tries="${2:-30}"; for ((i=1;i<=tries;i++)); do has_text "$wanted" && return 0; sleep .5; done; echo "Timed out: $wanted" >&2; ui_dump; cat "$UI_XML" >&2; return 1; }
find_point(){ local wanted="$1"; ui_dump; python3 - "$wanted" "$UI_XML" <<'PY'
import re,sys,xml.etree.ElementTree as ET
wanted,path=sys.argv[1],sys.argv[2]
for n in ET.parse(path).getroot().iter('node'):
    if n.attrib.get('text','')==wanted or n.attrib.get('content-desc','')==wanted:
        b=list(map(int,re.findall(r'\d+',n.attrib.get('bounds',''))))
        if len(b)==4: print((b[0]+b[2])//2,(b[1]+b[3])//2); raise SystemExit(0)
raise SystemExit(f'UI element not found: {wanted}')
PY
}
tap_text(){ local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .7; }
tap_text_fast(){ local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; }
start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Снять → проверить → передать" 40; }
open_data(){ start_app; tap_text "Учёт и история"; wait_text "Мои счётчики" 30; tap_text "Данные"; wait_text "Восстановить из копии" 30; }
select_download_file(){
  local file="$1"
  if ! has_text "$file"; then
    if has_text "Show roots"; then
      tap_text "Show roots"
      if has_text "Downloads"; then tap_text "Downloads"; fi
    fi
  fi
  if ! has_text "$file"; then
    wait_text "Search" 20
    tap_text "Search"
    sleep .5
    adb shell input text "$file"
    sleep 1
  fi
  wait_text "$file" 40
  tap_text "$file"
}
copy_db(){
  local dst="$1"
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  sleep .5
  rm -f "$dst" "$dst-wal" "$dst-shm"
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$dst"
  test -s "$dst"
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$dst-wal"
  fi
  if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then
    adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$dst-shm"
  fi
  python3 - "$dst" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
c.close()
PY
}
compare_logical(){
  local a="$1" b="$2" label="$3"
  python3 - "$a" "$b" "$label" <<'PY'
import json,sqlite3,sys
def logical(p):
 c=sqlite3.connect(p); out={}
 for t in ('addresses','metering_points','meters','readings','reading_values','tariff_schedule','submissions','submission_items'):
  cols=[r[1] for r in c.execute(f'pragma table_info({t})')]
  rows=[dict(zip(cols,r)) for r in c.execute(f'select * from {t}')]
  out[t]=sorted(rows,key=lambda x:json.dumps(x,sort_keys=True,ensure_ascii=False))
 assert c.execute('pragma foreign_key_check').fetchall()==[]
 assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
 c.close(); return out
assert logical(sys.argv[1])==logical(sys.argv[2]),f'{sys.argv[3]} changed live Room data'
print(f'{sys.argv[3]} preserved exact live Room dataset')
PY
}
staging_count(){ adb shell "ls -1d '$APP_PICTURES'/restore_staging_* 2>/dev/null | wc -l" | tr -d '\r[:space:]'; }

# The previous clean-restore regression creates a real v4 backup from the exact product tree.
test -s "$BASE_BACKUP"

# Build two adversarial fixtures without altering product code:
# 1) a valid restore that changes the address name and carries large (but individually valid)
#    photos, creating a long pre-commit staging window where a process kill can be observed;
# 2) a highly-compressed archive member that expands past the production 8 MiB JSON limit.
python3 - "$BASE_BACKUP" "$KILL_BACKUP" "$OVERSIZE_BACKUP" "$NAME_FILE" <<'PY'
import hashlib,json,sys,zipfile
src,kill_dst,oversize_dst,name_file=sys.argv[1:]
with zipfile.ZipFile(src) as z:
    files={n:z.read(n) for n in z.namelist() if n!='manifest.json' and not n.startswith('photos/')}
    old=json.loads(z.read('manifest.json'))
payload=json.loads(files['data.json'])
assert payload.get('addresses'),'source fixture has no addresses'
original=payload['addresses'][0]['name']
target=original+' · kill-test'
payload['addresses'][0]['name']=target
readings=[]
for a in payload['addresses']:
    for m in a.get('meters',[]): readings.extend(m.get('readings',[]))
assert readings,'source fixture has no readings'
# 12 MiB per reading is below MAX_PHOTO_BYTES=25 MiB. Repeated bytes compress well on the
# runner but still force the Android restore path to hash/write the full uncompressed payload.
photo=(b'KILL-RESTORE-STAGING-'*((12*1024*1024)//21+1))[:12*1024*1024]
for r in readings:
    r['hasPhoto']=True
    files[f"photos/{r['id']}.jpg"]=photo
files['data.json']=json.dumps(payload,ensure_ascii=False,indent=2).encode()
entries=[{'path':p,'size':len(d),'sha256':hashlib.sha256(d).hexdigest()} for p,d in files.items()]
manifest={'format':'moi-schetschiki-backup','version':4,'createdAt':old.get('createdAt',0),'entries':entries}
with zipfile.ZipFile(kill_dst,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode())
    for p,d in files.items(): z.writestr(p,d)
with open(name_file,'w',encoding='utf-8') as f:
    f.write(original+'\n'+target+'\n')
# data.json is deliberately first. Its compressed size is tiny, but extraction exceeds
# MAX_JSON_BYTES and must be rejected before any restore confirmation or live DB mutation.
with zipfile.ZipFile(oversize_dst,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('data.json',b'X'*(9*1024*1024))
    z.writestr('manifest.json',b'{}')
print(f'kill fixture: {len(readings)} readings x 12 MiB staged photos; target name={target!r}')
print('oversize fixture: 9 MiB extracted data.json (> 8 MiB production limit)')
PY

ORIGINAL_NAME=$(sed -n '1p' "$NAME_FILE")
TARGET_NAME=$(sed -n '2p' "$NAME_FILE")
test -n "$ORIGINAL_NAME" && test -n "$TARGET_NAME" && test "$ORIGINAL_NAME" != "$TARGET_NAME"
adb push "$KILL_BACKUP" /sdcard/Download/v14-kill-target.zip >/dev/null
adb push "$OVERSIZE_BACKUP" /sdcard/Download/v14-oversized-entry.zip >/dev/null
copy_db "$DB_BASE"

# Oversized extracted content must fail in the bounded parser and leave the live DB byte-logically unchanged.
open_data
tap_text "Восстановить из копии"
select_download_file "v14-oversized-entry.zip"
wait_text "Слишком большой файл в архиве" 60
if has_text "OK"; then tap_text "OK"; fi
copy_db "$DB_AFTER_OVERSIZE"
compare_logical "$DB_BASE" "$DB_AFTER_OVERSIZE" "oversized archive rejection"

# Kill during photo staging, before the Room commit point. A watcher starts before the confirm tap
# and kills the process as soon as a NEW restore_staging_* directory appears.
open_data
tap_text "Восстановить из копии"
select_download_file "v14-kill-target.zip"
wait_text "Восстановить резервную копию?" 30
before_count=$(staging_count)
[[ "$before_count" =~ ^[0-9]+$ ]]
KILL_MARKER="$GITHUB_WORKSPACE/v14-precommit-killed.txt"
rm -f "$KILL_MARKER"
(
  for _ in $(seq 1 300); do
    now=$(staging_count || echo 0)
    if [[ "$now" =~ ^[0-9]+$ ]] && (( now > before_count )); then
      adb shell am force-stop "$PACKAGE_NAME" >/dev/null
      echo "killed on staging count $before_count -> $now" > "$KILL_MARKER"
      exit 0
    fi
    sleep .02
  done
  echo "pre-commit watcher never observed a new staging directory" >&2
  exit 1
) &
watcher=$!
tap_text_fast "Восстановить"
wait "$watcher"
test -s "$KILL_MARKER"
cat "$KILL_MARKER"
sleep .5
copy_db "$DB_AFTER_PREKILL"
compare_logical "$DB_BASE" "$DB_AFTER_PREKILL" "pre-commit process kill"
python3 - "$DB_AFTER_PREKILL" "$TARGET_NAME" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
names=[r[0] for r in c.execute('select name from addresses order by id')]
assert sys.argv[2] not in names,('restore committed before pre-commit kill',names)
print('pre-commit kill did not expose target dataset')
PY
start_app

# Now complete the same restore normally. The success dialog is only shown after the Room commit
# and metadata application. Kill the process while that dialog is still visible, then prove the
# committed target dataset survives a cold restart and remains FK/integrity-clean.
open_data
tap_text "Восстановить из копии"
select_download_file "v14-kill-target.zip"
wait_text "Восстановить резервную копию?" 30
tap_text_fast "Восстановить"
wait_text "Резервная копия восстановлена" 120
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
sleep .5
copy_db "$DB_AFTER_POSTKILL"
python3 - "$DB_AFTER_POSTKILL" "$TARGET_NAME" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
names=[r[0] for r in c.execute('select name from addresses order by id')]
assert sys.argv[2] in names,('committed restore was lost after post-commit kill',names)
assert c.execute('pragma foreign_key_check').fetchall()==[]
assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
print('post-commit kill preserved committed target dataset')
c.close()
PY
start_app
wait_text "Снять → проверить → передать" 30

echo "v1.4 kill/oversize regression OK: oversized extracted archive is rejected without mutation; kill during staging preserves the old DB; kill after commit preserves the restored DB."