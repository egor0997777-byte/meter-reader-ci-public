#!/usr/bin/env bash
set -euo pipefail

source_script="$GITHUB_WORKSPACE/.github/scripts/android-v14-fault-regression.sh"
temp_script="$RUNNER_TEMP/android-v14-fault-regression-compat.sh"

python3 - "$source_script" "$temp_script" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()
old = '''select_download_file(){
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
'''
new = '''select_download_file(){
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
'''
if old not in src:
    raise SystemExit("Expected select_download_file block missing")
Path(sys.argv[2]).write_text(src.replace(old, new))
PY

bash "$temp_script"
