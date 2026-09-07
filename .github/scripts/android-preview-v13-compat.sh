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
Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
