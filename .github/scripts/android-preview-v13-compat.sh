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
replacements = {
    'wait_text "Всё хранится на устройстве"': 'wait_text "Снять → проверить → передать"',
    'tap_text "Безопасность и конфиденциальность"; wait_text "Где находятся данные"; wait_text "Интернет-разрешение не запрашивается"': 'tap_text "Безопасность и конфиденциальность"; wait_text "Версия 1.0"; tap_text "Безопасность и конфиденциальность"; wait_text "Где находятся данные"; wait_text "Интернет-разрешение не запрашивается"',
    'tap_text "‹  Главная"; wait_text "Учёт показаний"': 'tap_text "‹  Главная"; wait_text "Учёт показаний"; adb shell input keyevent KEYCODE_BACK >/dev/null; wait_text "Учёт и история"',
    'tap_text "Учёт показаний"': 'tap_text "Учёт и история"',
}
for old, new in replacements.items():
    if old not in src:
        raise SystemExit(f"Expected legacy preview marker missing: {old}")
    src = src.replace(old, new)
Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
