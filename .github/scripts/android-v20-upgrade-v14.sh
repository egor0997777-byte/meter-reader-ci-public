#!/usr/bin/env bash
set -euo pipefail

CURRENT_APK="$GITHUB_WORKSPACE/meter-reader/app/build/outputs/apk/debug/app-debug.apk"
V14_BRANCH="ci-fix-v1.4"
V14_EXPECTED_TREE="0bd8f07848eb679b155e5027b82fcdbb03babb9d"
WORKTREE="$RUNNER_TEMP/meter-reader-v14-upgrade"
V14_DB_DIR="$RUNNER_TEMP/meter-reader-v14-before"
V20_DB_DIR="$RUNNER_TEMP/meter-reader-v20-after-v14-upgrade"

pull_room_snapshot() {
  local target_dir="$1"
  rm -rf "$target_dir"
  mkdir -p "$target_dir"
  adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$target_dir/meter-reader.db"
  test -s "$target_dir/meter-reader.db"
  for suffix in -wal -shm; do
    if adb shell "run-as $PACKAGE_NAME test -f databases/meter-reader.db${suffix}" >/dev/null 2>&1; then
      adb exec-out run-as "$PACKAGE_NAME" cat "databases/meter-reader.db${suffix}" > "$target_dir/meter-reader.db${suffix}"
    fi
  done
}

# ci-fix-v1.4 was independently proven to contain the exact meter-reader tree
# merged into private main for v1.4. Build it in the same job, then re-sign test-only
# APK copies with one ephemeral CI certificate. GitHub-hosted jobs intentionally have
# ephemeral debug keystores, and the emulator action may recreate Android user state;
# package-manager upgrade validation requires both APKs to have the same signer.
# The original product APKs are left untouched and remain the packaging artifacts.
git fetch --depth=1 origin "$V14_BRANCH"
rm -rf "$WORKTREE"
git worktree add --detach "$WORKTREE" FETCH_HEAD
actual_tree=$(git -C "$WORKTREE" rev-parse HEAD:meter-reader)
if [[ "$actual_tree" != "$V14_EXPECTED_TREE" ]]; then
  echo "Unexpected v1.4 meter-reader tree: $actual_tree" >&2
  exit 1
fi
grep -q 'versionCode = 140' "$WORKTREE/meter-reader/app/build.gradle.kts"
grep -q 'versionName = "1.4.0"' "$WORKTREE/meter-reader/app/build.gradle.kts"
gradle -p "$WORKTREE/meter-reader" :app:assembleDebug --stacktrace
V14_APK="$WORKTREE/meter-reader/app/build/outputs/apk/debug/app-debug.apk"
test -s "$V14_APK"
test -s "$CURRENT_APK"

APKSIGNER=$(find "${ANDROID_HOME:?}/build-tools" -type f -name apksigner -perm -111 | sort -V | tail -n 1)
test -x "$APKSIGNER"
command -v keytool >/dev/null

echo "Original v1.4 debug signer:"
"$APKSIGNER" verify --print-certs "$V14_APK"
echo "Original v2.0 debug signer:"
"$APKSIGNER" verify --print-certs "$CURRENT_APK"

CI_KEYSTORE="$RUNNER_TEMP/meter-reader-upgrade-ci.p12"
CI_STOREPASS="meter-reader-ci-upgrade"
CI_ALIAS="meter-reader-ci-upgrade"
rm -f "$CI_KEYSTORE"
keytool -genkeypair -noprompt \
  -keystore "$CI_KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$CI_STOREPASS" \
  -keypass "$CI_STOREPASS" \
  -alias "$CI_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -dname "CN=Meter Reader CI Upgrade Test,O=CI,C=US" >/dev/null

V14_TEST_APK="$RUNNER_TEMP/meter-reader-v14-upgrade-test.apk"
V20_TEST_APK="$RUNNER_TEMP/meter-reader-v20-upgrade-test.apk"
cp "$V14_APK" "$V14_TEST_APK"
cp "$CURRENT_APK" "$V20_TEST_APK"
for apk in "$V14_TEST_APK" "$V20_TEST_APK"; do
  "$APKSIGNER" sign \
    --ks "$CI_KEYSTORE" \
    --ks-key-alias "$CI_ALIAS" \
    --ks-pass "pass:$CI_STOREPASS" \
    --key-pass "pass:$CI_STOREPASS" \
    "$apk"
  "$APKSIGNER" verify --print-certs "$apk"
done

signer_digest() {
  "$APKSIGNER" verify --print-certs "$1" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}'
}
v14_signer=$(signer_digest "$V14_TEST_APK")
v20_signer=$(signer_digest "$V20_TEST_APK")
test -n "$v14_signer"
test -n "$v20_signer"
if [[ "$v14_signer" != "$v20_signer" ]]; then
  echo "CI upgrade APK signer mismatch after test-only signing" >&2
  exit 1
fi
echo "CI upgrade signer equality proven: $v14_signer"

adb uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
adb install "$V14_TEST_APK" >/dev/null

# Use the same legacy JSON shape already exercised by the v1.x regression suite.
# v1.4 itself owns the conversion of this state into Room before the package update.
legacy_json='[{"id":"upgrade-v14-address","name":"Upgrade v1.4 Home","meters":[{"id":"upgrade-v14-meter","name":"Холодная вода","unit":"м³","kind":"cold_water","serial":"V14-CW","readings":[{"id":"upgrade-v14-reading","value":123.456,"timestamp":1788200000000,"note":"from v1.4"}]}]}]'
escaped=$(python3 - "$legacy_json" <<'PY'
import html,sys
print(html.escape(sys.argv[1], quote=True))
PY
)
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' "    <string name=\"addresses\">$escaped</string>" '</map>' > "$RUNNER_TEMP/v14-meters.xml"
adb push "$RUNNER_TEMP/v14-meters.xml" /data/local/tmp/v14-meters.xml >/dev/null
adb shell "run-as $PACKAGE_NAME mkdir -p shared_prefs && run-as $PACKAGE_NAME cp /data/local/tmp/v14-meters.xml shared_prefs/meters.xml"
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
sleep 2
adb shell am force-stop "$PACKAGE_NAME" >/dev/null

# Room uses WAL. A bare copy of meter-reader.db can expose an old header/schema
# even though the live database is valid. Pull the complete DB family so SQLite
# evaluates exactly the durable state that Android will preserve across install -r.
pull_room_snapshot "$V14_DB_DIR"
python3 - "$V14_DB_DIR/meter-reader.db" <<'PY'
import sqlite3,sys
p=sys.argv[1]
db=sqlite3.connect(p)
version=db.execute('pragma user_version').fetchone()[0]
print(f'v1.4 pre-upgrade user_version={version}')
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
assert version == 5, version
assert db.execute("select name from addresses where id='upgrade-v14-address'").fetchone() == ('Upgrade v1.4 Home',)
assert db.execute("select name,serial from meters where id='upgrade-v14-meter'").fetchone() == ('Холодная вода','V14-CW')
row=db.execute("select valueText from reading_values where readingId='upgrade-v14-reading' and zone='TOTAL'").fetchone()
assert row and row[0] == '123.456', row
print('v1.4 pre-upgrade state OK')
PY

# Real package-manager update: no uninstall/data clear between v1.4 and v2.0.
adb install -r "$V20_TEST_APK" >/dev/null
version=$(adb shell dumpsys package "$PACKAGE_NAME" | sed -n 's/.*versionName=//p' | head -n1 | tr -d '\r')
[[ "$version" == "2.0.0" ]] || { echo "Expected v2.0.0 after upgrade, got $version" >&2; exit 1; }
adb shell am start -W -n "$PACKAGE_NAME/$PACKAGE_NAME.V13MainActivity" >/dev/null
sleep 2
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
pull_room_snapshot "$V20_DB_DIR"
python3 - "$V20_DB_DIR/meter-reader.db" <<'PY'
import sqlite3,sys
p=sys.argv[1]
db=sqlite3.connect(p)
version=db.execute('pragma user_version').fetchone()[0]
print(f'v2.0 post-upgrade user_version={version}')
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
assert db.execute('pragma foreign_key_check').fetchall() == []
assert version == 5, version
assert db.execute("select name from addresses where id='upgrade-v14-address'").fetchone() == ('Upgrade v1.4 Home',)
assert db.execute("select name,serial from meters where id='upgrade-v14-meter'").fetchone() == ('Холодная вода','V14-CW')
row=db.execute("select valueText from reading_values where readingId='upgrade-v14-reading' and zone='TOTAL'").fetchone()
assert row and row[0] == '123.456', row
print('v1.4 -> v2.0 in-place upgrade OK: Room data and exact reading text preserved')
PY

git worktree remove --force "$WORKTREE" || true
