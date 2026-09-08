#!/usr/bin/env bash
set -euo pipefail

UI_XML="$GITHUB_WORKSPACE/v14-enospc-window.xml"
BASE_BACKUP="$GITHUB_WORKSPACE/v14-clean-device-backup.zip"
ENOSPC_BACKUP="$GITHUB_WORKSPACE/v14-enospc.zip"
DB_BEFORE="$GITHUB_WORKSPACE/v14-enospc-before.db"
DB_AFTER="$GITHUB_WORKSPACE/v14-enospc-after.db"
FILLER="/sdcard/Download/v14-enospc-fill.bin"
APP_PICTURES="/storage/emulated/0/Android/data/$PACKAGE_NAME/files/Pictures"
REQUIRED_KB=$((9 * 1024))

cleanup(){ adb shell rm -f "$FILLER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

ui_dump(){ adb shell uiautomator dump --compressed /sdcard/v14-enospc-window.xml >/dev/null; adb pull /sdcard/v14-enospc-window.xml "$UI_XML" >/dev/null; }
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
start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Снять → проверить → передать"; }
open_data(){ start_app; tap_text "Учёт и история"; wait_text "Мои счётчики"; tap_text "Данные"; wait_text "Восстановить из копии"; }
select_download_file(){ local file="$1"; if ! has_text "$file"; then wait_text "Show roots" 20; tap_text "Show roots"; wait_text "Downloads" 20; tap_text "Downloads"; fi; wait_text "$file" 40; tap_text "$file"; }
copy_db(){ local dst="$1"; adb shell am force-stop "$PACKAGE_NAME" >/dev/null; sleep .4; adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$dst"; test -s "$dst"; }
available_kb(){
  local path="$1"
  adb shell "df -Pk '$path' 2>/dev/null" | tr -d '\r' | tail -n 1 | awk '{print $4}'
}

# Build a valid v4 archive with an 8 MiB photo. Restore needs the full 8 MiB plus
# the production 1 MiB safety margin, i.e. just over 9 MiB of app-visible free space.
test -s "$BASE_BACKUP"
python3 - "$BASE_BACKUP" "$ENOSPC_BACKUP" <<'PY'
import hashlib,json,sys,zipfile
src,dst=sys.argv[1],sys.argv[2]
with zipfile.ZipFile(src) as z:
    files={n:z.read(n) for n in z.namelist() if n!='manifest.json'}
    old=json.loads(z.read('manifest.json'))
payload=json.loads(files['data.json'])
reading=None
for a in payload['addresses']:
    for m in a.get('meters',[]):
        if m.get('readings'):
            reading=m['readings'][0]; break
    if reading: break
assert reading
reading['hasPhoto']=True
files['data.json']=json.dumps(payload,ensure_ascii=False,indent=2).encode()
files[f"photos/{reading['id']}.jpg"]=b'X'*(8*1024*1024)
entries=[{'path':p,'size':len(d),'sha256':hashlib.sha256(d).hexdigest()} for p,d in files.items()]
manifest={'format':'moi-schetschiki-backup','version':4,'createdAt':old.get('createdAt',0),'entries':entries}
with zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode())
    for p,d in files.items(): z.writestr(p,d)
PY
adb push "$ENOSPC_BACKUP" /sdcard/Download/v14-enospc.zip >/dev/null
copy_db "$DB_BEFORE"

# The production guard calls StatFs on getExternalFilesDir(Pictures), not on /sdcard/Download.
# Measure the app's actual external-files filesystem and size the filler from that same mount.
echo "ENOSPC filesystem diagnostics before fill:"
adb shell "df -Pk /sdcard || true"
adb shell "df -Pk '$APP_PICTURES' || true"
adb shell "stat -f '$APP_PICTURES' 2>/dev/null || true"
app_avail_kb=$(available_kb "$APP_PICTURES")
if ! [[ "$app_avail_kb" =~ ^[0-9]+$ ]]; then
  echo "Could not measure free space on app pictures path: $APP_PICTURES" >&2
  exit 1
fi
fill_mb=$(( app_avail_kb / 1024 - 4 ))
if (( fill_mb <= 16 )); then
  echo "Unexpectedly low free space before ENOSPC setup: ${app_avail_kb} KiB at $APP_PICTURES" >&2
  exit 1
fi
adb shell "dd if=/dev/zero of='$FILLER' bs=1048576 count=$fill_mb >/dev/null 2>&1 || true"

remaining_kb=$(available_kb "$APP_PICTURES")
echo "ENOSPC filesystem diagnostics after fill:"
adb shell "df -Pk /sdcard || true"
adb shell "df -Pk '$APP_PICTURES' || true"
adb shell "stat -f '$APP_PICTURES' 2>/dev/null || true"
if ! [[ "$remaining_kb" =~ ^[0-9]+$ ]]; then
  echo "Could not re-measure app pictures free space" >&2
  exit 1
fi
if (( remaining_kb >= REQUIRED_KB )); then
  echo "Could not create deterministic low-space condition on app pictures filesystem: ${remaining_kb} KiB remain" >&2
  exit 1
fi

echo "ENOSPC precondition proven: ${remaining_kb} KiB remain at $APP_PICTURES (< ${REQUIRED_KB} KiB)"
open_data
tap_text "Восстановить из копии"
select_download_file "v14-enospc.zip"
wait_text "Восстановить резервную копию?" 30
tap_text "Восстановить"
wait_text "Недостаточно свободного места для восстановления фото" 40
if has_text "OK"; then tap_text "OK"; fi

cleanup
copy_db "$DB_AFTER"
python3 - "$DB_BEFORE" "$DB_AFTER" <<'PY'
import json,sqlite3,sys
def logical(p):
 c=sqlite3.connect(p); out={}
 for t in ('addresses','metering_points','meters','readings','reading_values','tariff_schedule','submissions','submission_items'):
  cols=[r[1] for r in c.execute(f'pragma table_info({t})')]
  rows=[dict(zip(cols,r)) for r in c.execute(f'select * from {t}')]
  out[t]=sorted(rows,key=lambda x:json.dumps(x,sort_keys=True,ensure_ascii=False))
 assert c.execute('pragma foreign_key_check').fetchall()==[]
 assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
 return out
assert logical(sys.argv[1])==logical(sys.argv[2]),'ENOSPC restore changed live Room data'
print('ENOSPC restore preserved exact live Room dataset')
PY

echo "v1.4 ENOSPC regression OK: low-space restore is rejected before swap and live Room data remains exact."
