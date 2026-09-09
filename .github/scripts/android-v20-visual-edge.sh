#!/usr/bin/env bash
set -euo pipefail

ARTIFACTS="$GITHUB_WORKSPACE/android-preview-artifacts"
UI_XML="$GITHUB_WORKSPACE/window-v20-visual.xml"
DB_LOCAL="$RUNNER_TEMP/v20-visual.db"
mkdir -p "$ARTIFACTS"

reset_display() {
  adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb shell wm size reset >/dev/null 2>&1 || true
}
trap reset_display EXIT

ui_dump() {
  adb shell uiautomator dump --compressed /sdcard/window-v20-visual.xml >/dev/null
  adb pull /sdcard/window-v20-visual.xml "$UI_XML" >/dev/null
}

has_text() {
  local wanted="$1"
  ui_dump
  python3 - "$wanted" "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
wanted, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).getroot().iter('node'):
    if wanted in node.attrib.get('text', '') or wanted in node.attrib.get('content-desc', ''):
        sys.exit(0)
sys.exit(1)
PY
}

wait_text() {
  local wanted="$1"
  local tries="${2:-20}"
  for ((i=1; i<=tries; i++)); do
    if has_text "$wanted"; then return 0; fi
    sleep .5
  done
  echo "Timed out waiting for: $wanted" >&2
  ui_dump
  cat "$UI_XML" >&2
  return 1
}

tap_text() {
  local wanted="$1"
  ui_dump
  local point
  point=$(python3 - "$wanted" "$UI_XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
wanted, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).getroot().iter('node'):
    text = node.attrib.get('text', '')
    desc = node.attrib.get('content-desc', '')
    if text == wanted or desc == wanted:
        nums = list(map(int, re.findall(r'\d+', node.attrib.get('bounds', ''))))
        if len(nums) == 4:
            print((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)
            sys.exit(0)
print(f"UI element not found: {wanted}", file=sys.stderr)
sys.exit(2)
PY
)
  read -r x y <<<"$point"
  adb shell input tap "$x" "$y"
  sleep .5
}

shot() {
  sleep .5
  adb exec-out screencap -p > "$ARTIFACTS/$1"
  test -s "$ARTIFACTS/$1"
}

# Start from a clean install state so earlier fault/app-lock regressions cannot
# influence this purely visual v2.0 fixture.
adb shell pm clear "$PACKAGE_NAME" >/dev/null
adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null
wait_text "Мои счётчики"
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
sleep .5

# Copy the freshly initialized Room database (including WAL if present), make a
# deterministic current-period fixture with deliberately long labels, then
# checkpoint it before putting it back in the app sandbox.
rm -f "$DB_LOCAL" "$DB_LOCAL-wal" "$DB_LOCAL-shm"
adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$DB_LOCAL"
test -s "$DB_LOCAL"
if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-wal"; then
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-wal > "$DB_LOCAL-wal"
fi
if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db-shm"; then
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db-shm > "$DB_LOCAL-shm"
fi

python3 - "$DB_LOCAL" <<'PY'
import datetime as dt
import sqlite3
import sys

path = sys.argv[1]
period = dt.datetime.now(dt.timezone.utc).strftime('%Y-%m')
now_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
long_address = 'Квартира с очень длинным названием — Красноказарменная, дом 14, корпус 1'
long_meter = 'Холодная вода в дальней ванной комнате рядом со стиральной машиной'

con = sqlite3.connect(path)
con.execute('PRAGMA foreign_keys=ON')
con.execute('DELETE FROM addresses')
con.execute(
    'INSERT INTO addresses(id,name,account,recipient) VALUES(?,?,?,?)',
    ('visual-address', long_address, '12345678901234567890', 'Управляющая компания с очень длинным названием')
)
con.execute(
    'INSERT INTO metering_points(id,addressId,name,unit,kind,location,account,recipient,status) VALUES(?,?,?,?,?,?,?,?,?)',
    ('visual-point', 'visual-address', long_meter, 'м³', 'cold_water', 'Очень длинное описание места установки счётчика', '', '', 'active')
)
con.execute(
    '''INSERT INTO meters(
        id,addressId,name,unit,kind,serial,integerDigits,fractionDigits,previousMeterId,
        installedAt,verificationUntil,status,account,recipient,tariffZones,location,meteringPointId
    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
    ('visual-meter', 'visual-address', long_meter, 'м³', 'cold_water', 'CW-VERY-LONG-000000000001',
     6, 3, None, None, None, 'active', '', '', 'TOTAL',
     'Очень длинное описание места установки счётчика', 'visual-point')
)
con.execute(
    'INSERT INTO readings(id,meterId,timestamp,photoUri,note,source,billingPeriod) VALUES(?,?,?,?,?,?,?)',
    ('visual-reading', 'visual-meter', now_ms, None, 'Длинная заметка для визуальной проверки интерфейса', 'manual', period)
)
con.execute(
    'INSERT INTO reading_values(readingId,zone,valueText) VALUES(?,?,?)',
    ('visual-reading', 'TOTAL', '000123.456')
)
con.commit()
con.execute('PRAGMA wal_checkpoint(TRUNCATE)')
con.close()
print('v2 visual fixture created for', period)
PY

adb push "$DB_LOCAL" /data/local/tmp/v20-visual.db >/dev/null
adb shell "run-as $PACKAGE_NAME cp /data/local/tmp/v20-visual.db databases/meter-reader.db && rm -f databases/meter-reader.db-wal databases/meter-reader.db-shm"

# Approx. 274 x 488 dp on the Pixel 6 profile plus 130% system font: a compact,
# intentionally stressful viewport rather than the normal 411 x 914 dp device.
adb shell wm size 720x1280 >/dev/null
adb shell settings put system font_scale 1.3 >/dev/null
adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null
wait_text "Мои счётчики"
wait_text "Проверить и передать"
shot "24-v20-small-screen-large-font-home.png"

tap_text "Проверить и передать"
wait_text "Показания готовы"
wait_text "Квартира с очень длинным названием"
wait_text "Холодная вода в дальней ванной"
shot "25-v20-small-screen-long-names-summary.png"

# Prove the summary remains scrollable and its transmission controls can still
# be reached on the compact viewport.
for _ in 1 2 3 4; do
  if has_text "Передача"; then break; fi
  adb shell input swipe 360 1050 360 260 350 >/dev/null
  sleep .4
done
wait_text "Передача"
shot "26-v20-small-screen-long-names-transfer.png"

echo "v2.0 visual edge regression OK: small screen, 130% font, long address/meter labels, scrollable summary."
