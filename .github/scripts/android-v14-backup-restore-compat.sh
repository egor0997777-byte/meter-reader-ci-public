#!/usr/bin/env bash
set -euo pipefail

source_script="$GITHUB_WORKSPACE/.github/scripts/android-v14-backup-restore.sh"
temp_script="$RUNNER_TEMP/android-v14-backup-restore-compat.sh"

python3 - "$source_script" "$temp_script" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()
old = '''# DocumentsUI may open on an empty Recent view even though the file is present in Download.
# Navigate explicitly to Downloads instead of treating Recent indexing as an app failure.
if ! has_text "moi-schetschiki-backup.zip"; then
  wait_text "Show roots" 20
  tap_text "Show roots"
  wait_text "Downloads" 20
  tap_text "Downloads"
fi
wait_text "moi-schetschiki-backup.zip" 40
'''
new = '''# DocumentsUI may open on an empty Recent view even though the file is present in Download.
# First try Downloads. If that view still does not expose the file, use the actual DocumentsUI
# Search affordance observed on API 35. This keeps the regression on the production SAF flow
# while avoiding dependence on Recent indexing and drawer-navigation quirks.
if ! has_text "moi-schetschiki-backup.zip"; then
  if has_text "Show roots"; then
    tap_text "Show roots"
    if has_text "Downloads"; then tap_text "Downloads"; fi
  fi
fi
if ! has_text "moi-schetschiki-backup.zip"; then
  wait_text "Search" 20
  tap_text "Search"
  sleep .5
  adb shell input text "moi-schetschiki-backup.zip"
  sleep 1
fi
wait_text "moi-schetschiki-backup.zip" 40
'''
if old not in src:
    raise SystemExit("Expected DocumentsUI picker block missing")
Path(sys.argv[2]).write_text(src.replace(old, new))
PY

bash "$temp_script"
