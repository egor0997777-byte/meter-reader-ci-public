#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="$GITHUB_WORKSPACE/meter-reader/app/build/outputs/apk/debug/app-debug.apk"
ARTIFACT_ZIP="${V10_ARTIFACT_ZIP:-$RUNNER_TEMP/android-v1-release.zip}"
V10_UNSIGNED="$RUNNER_TEMP/app-release-v1.0-unsigned.apk"
V10_TEST_APK="$RUNNER_TEMP/meter-reader-v10-upgrade-test.apk"
V20_TEST_APK="$RUNNER_TEMP/meter-reader-v20-from-v10-upgrade-test.apk"
UI_XML="$RUNNER_TEMP/v10-upgrade-window.xml"
PACKAGE_NAME="${PACKAGE_NAME:-ru.egor.meters}"
V10_ARTIFACT_SHA256="9c445c371b7610da3519cc539d46500cf5f5b45a0d0639d4477fe95c9911afd3"

test -s "$CURRENT_APK"
test -s "$ARTIFACT_ZIP"
command -v unzip >/dev/null
command -v keytool >/dev/null

echo "$V10_ARTIFACT_SHA256  $ARTIFACT_ZIP" | sha256sum -c -
unzip -p "$ARTIFACT_ZIP" apk/release/app-release-unsigned.apk > "$V10_UNSIGNED"
test -s "$V10_UNSIGNED"

AAPT=$(find "${ANDROID_HOME:?}/build-tools" -type f -name aapt -perm -111 | sort -V | tail -n 1)
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -perm -111 | sort -V | tail -n 1)
test -x "$AAPT"
test -x "$APKSIGNER"

# Capture the complete output first. With `set -o pipefail`, piping aapt into
# `head -n 1` can make aapt exit with SIGPIPE (141) even though badging is valid.
v10_badging=$($AAPT dump badging "$V10_UNSIGNED")
printf '%s\n' "$v10_badging" | sed -n '1p'
grep -q "package: name='ru.egor.meters'" <<<"$v10_badging"
grep -q "versionCode='100'" <<<"$v10_badging"
grep -q "versionName='1.0.0'" <<<"$v10_badging"

CI_KEYSTORE="$RUNNER_TEMP/meter-reader-v10-upgrade-ci.p12"
CI_STOREPASS="meter-reader-ci-upgrade-v10"
CI_ALIAS="meter-reader-ci-upgrade-v10"
rm -f "$CI_KEYSTORE"
keytool -genkeypair -noprompt \
  -keystore "$CI_KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$CI_STOREPASS" \
  -keypass "$CI_STOREPASS" \
  -alias "$CI_ALIAS" \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=Meter Reader v1.0 Upgrade CI,O=CI,C=US" >/dev/null

cp "$V10_UNSIGNED" "$V10_TEST_APK"
cp "$CURRENT_APK" "$V20_TEST_APK"
for apk in "$V10_TEST_APK" "$V20_TEST_APK"; do
  "$APKSIGNER" sign \
    --ks "$CI_KEYSTORE" \
    --ks-key-alias "$CI_ALIAS" \
    --ks-pass "pass:$CI_STOREPASS" \
    --key-pass "pass:$CI_STOREPASS" \
    "$apk"
  "$APKSIGNER" verify --print-certs "$apk"
done

signer_digest() {
  "$APKSIGNER" verify --print-certs "$1" | awk -F': ' '/certificate SHA-256 digest:/ {print $2; exit}'
}
v10_signer=$(signer_digest "$V10_TEST_APK")
v20_signer=$(signer_digest "$V20_TEST_APK")
test -n "$v10_signer"
test "$v10_signer" = "$v20_signer"
echo "v1.0/v2.0 CI signer equality proven: $v10_signer"

ui_dump() {
  adb shell uiautomator dump --compressed /sdcard/v10-upgrade-window.xml >/dev/null
  adb pull /sdcard/v10-upgrade-window.xml "$UI_XML" >/dev/null
}
find_point() {
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
print(f'UI element not found: {wanted}', file=sys.stderr)
sys.exit(2)
PY
}
has_text() {
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
wait_text() {
  local wanted="$1"
  for _ in {1..30}; do
    if has_text "$wanted"; then return 0; fi
    sleep .5
  done
  echo "Timed out waiting for: $wanted" >&2
  ui_dump; cat "$UI_XML" >&2
  return 1
}
tap_text() {
  local p; p=$(find_point "$1"); read -r x y <<<"$p"; adb shell input tap "$x" "$y"; sleep .5
}
replace_text() {
  tap_text "$1"
  adb shell input keyevent KEYCODE_MOVE_END
  for _ in {1..40}; do adb shell input keyevent KEYCODE_DEL >/dev/null; done
  adb shell input text "$2"
  sleep .3
}
start_app() {
  local ready_text="${1:-Всё хранится на устройстве}"
  adb shell am force-stop "$PACKAGE_NAME" >/dev/null
  adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null 2>&1 || \
    adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V013MainActivity" >/dev/null
  wait_text "$ready_text"
}

adb uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
adb install "$V10_TEST_APK" >/dev/null
start_app

tap_text "Учёт показаний"
wait_text "Добавить адрес"
tap_text "Добавить адрес"
wait_text "Новый адрес"
replace_text "Название или адрес" "UpgradeV10"
tap_text "Добавить"
wait_text "UpgradeV10"
tap_text "UpgradeV10"
wait_text "Добавить счётчик"
tap_text "Добавить счётчик"
wait_text "Новый счётчик"
tap_text "Добавить"
wait_text "Холодная вода"
adb shell am force-stop "$PACKAGE_NAME" >/dev/null

adb install -r "$V20_TEST_APK" >/dev/null
version=$(adb shell dumpsys package "$PACKAGE_NAME" | sed -n 's/.*versionName=//p' | tr -d '\r' | tail -n 1)
[[ "$version" == "2.0.0" ]] || { echo "Expected v2.0.0 after v1.0 upgrade, got $version" >&2; exit 1; }
# v2.0 intentionally has a status-first hub instead of the old v1.x privacy copy.
# Wait for the new hub marker, then independently prove the v1.0 address survived.
start_app "Снять → проверить → передать"
wait_text "UpgradeV10"

adb shell am force-stop "$PACKAGE_NAME" >/dev/null
DB="$RUNNER_TEMP/meter-reader-after-v10-upgrade.db"
adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$DB"
test -s "$DB"
python3 - "$DB" <<'PY'
import sqlite3,sys
p=sys.argv[1]
db=sqlite3.connect(p)
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
assert db.execute('pragma foreign_key_check').fetchall() == []
version=db.execute('pragma user_version').fetchone()[0]
assert version == 5, version
assert db.execute("select count(*) from addresses where name='UpgradeV10'").fetchone()[0] == 1
row=db.execute("select m.name from meters m join addresses a on a.id=m.addressId where a.name='UpgradeV10' and m.status='active' order by m.rowid limit 1").fetchone()
assert row and row[0] == 'Холодная вода', row
print('v1.0 -> v2.0 in-place upgrade OK: package update, Room integrity, address and meter preserved')
PY
