#!/usr/bin/env bash
set -euo pipefail

source_script="$GITHUB_WORKSPACE/.github/scripts/android-v11-flow.sh"
temp_script="$RUNNER_TEMP/android-v11-v14-compat.sh"

python3 - "$source_script" "$temp_script" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()

# v1.4 keeps the same monthly walkthrough, but when a single configured address
# already has readings for the current period the hub CTA is intentionally
# "Снять показания заново" instead of the older "Снять показания".
wait_old = 'wait_text "Снять показания"'
tap_old = 'tap_text "Снять показания"'
if wait_old not in src or tap_old not in src:
    raise SystemExit("Expected v1.3 hub CTA markers missing")
src = src.replace(wait_old, 'wait_text "Снять показания заново"')
src = src.replace(tap_old, 'tap_text "Снять показания заново"')

Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
