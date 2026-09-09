#!/usr/bin/env bash
set -euo pipefail

# The long-running legacy regression script still validates v0.7-v1.2 flows,
# while v1.3 adds a new monthly-flow hub in front of the legacy v0.13 launcher.
# Patch only assumptions that actually changed in the v1.3 launcher.
source_script="$GITHUB_WORKSPACE/.github/scripts/android-preview.sh"
temp_script="$RUNNER_TEMP/android-preview-v13-compat.sh"

python3 - "$source_script" "$temp_script" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()

# The current manifest intentionally contains INTERNET/ACCESS_NETWORK_STATE with
# tools:node="remove". The workflow validates the built APK with aapt before
# launching the emulator, so the old raw-source grep is now a false positive.
legacy_permission_check = '''# Privacy/security invariant: the app must not request hidden network access.\nif grep -q 'android.permission.INTERNET' "$GITHUB_WORKSPACE/meter-reader/app/src/main/AndroidManifest.xml"; then\n  echo "Unexpected INTERNET permission" >&2\n  exit 1\nfi\n\n'''
if legacy_permission_check not in src:
    raise SystemExit("Expected legacy raw-manifest permission check missing")
src = src.replace(legacy_permission_check, '# Built APK permissions are validated by the workflow before emulator launch.\n\n')

replacements = {
    'wait_text "Всё хранится на устройстве"': 'wait_text "Снять → проверить → передать"',
    'tap_text "Безопасность и конфиденциальность"; wait_text "Где находятся данные"; wait_text "Интернет-разрешение не запрашивается"': 'tap_text "Безопасность и конфиденциальность"; wait_text "Версия 1.0"; tap_text "Безопасность и конфиденциальность"; wait_text "Где находятся данные"; wait_text "Интернет-разрешение не запрашивается"',
    'tap_text "‹  Главная"; wait_text "Учёт показаний"': 'tap_text "‹  Главная"; wait_text "Учёт показаний"; adb shell input keyevent KEYCODE_BACK >/dev/null; wait_text "Учёт и история"',
    'tap_text "Учёт показаний"': 'tap_text "Учёт и история"',
    'tap_text "Сохранить"; wait_text "T1 100"': 'hide_ime; tap_text "Сохранить"; wait_text "T1 100"',
    'tap_text "Сохранить"; wait_text "Расход с прошлого раза"': 'hide_ime; tap_text "Сохранить"; wait_text "Расход с прошлого раза"',
}
for old, new in replacements.items():
    if old not in src:
        raise SystemExit(f"Expected legacy preview marker missing: {old}")
    src = src.replace(old, new)

# GitHub's API 35 emulator occasionally surfaces an ANR dialog from Pixel Launcher
# immediately after boot. That system dialog can cover the app even though the APK
# installed and the activity launched successfully. Dismiss only this known launcher
# ANR and retry the app launch once; product assertions remain unchanged.
old_start_app = 'start_app(){ adb shell am force-stop "$PACKAGE_NAME" >/dev/null; adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null; wait_text "Снять → проверить → передать"; }'
new_start_app = '''start_app(){
 adb shell am force-stop "$PACKAGE_NAME" >/dev/null
 adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null
 if ! wait_text "Снять → проверить → передать" 6; then
   ui_dump
   if grep -Fq "Pixel Launcher isn't responding" "$UI_XML"; then
     echo "Dismissing Pixel Launcher ANR and retrying app launch" >&2
     tap_text "Close app"
     adb shell am force-stop "$PACKAGE_NAME" >/dev/null
     adb shell am start -W -f 0x10008000 -n "$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY" >/dev/null
     wait_text "Снять → проверить → передать" 20
   else
     echo "Preview launch failed without the known Pixel Launcher ANR" >&2
     return 1
   fi
 fi
}'''
if old_start_app not in src:
    raise SystemExit("Expected patched start_app marker missing")
src = src.replace(old_start_app, new_start_app, 1)

# Keep the tariff-cost fixture deterministic across a UTC/local midnight rollover.
# The production estimator correctly requires a tariff to be active on the later
# reading of each interval. The legacy script used the editor's default "today",
# so a CI run crossing midnight could make the just-created tariff newer than the
# readings it is meant to price. Anchor all three tariffs at this month's first day;
# the expected 10*6 + 5*3 + 2*2 = 79 calculation remains unchanged.
tariff_editor_open = 'wait_text "Электричество"; tap_nth_text "Тарифы" 2; wait_text "История тарифов"; wait_text "Электричество"'
if tariff_editor_open not in src:
    raise SystemExit("Expected tariff editor marker missing")
src = src.replace(
    tariff_editor_open,
    tariff_editor_open + '; replace_text "Действует с" "$(date +01.%m.%Y)"',
    1,
)

Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
