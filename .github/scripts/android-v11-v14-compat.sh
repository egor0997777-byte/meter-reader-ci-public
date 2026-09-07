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
replacements = {
    'wait_text "Снять показания"': 'wait_text "Снять показания заново"',
    'tap_text "Снять показания"': 'tap_text "Снять показания заново"',
    # Hardened v1.4 validates configured/default meter digit counts before save.
    # Cold water defaults to five integer digits, so the old six-digit 000013
    # fixture is invalid. Use the equivalent valid five-digit fixture and keep
    # the DB/submission assertions in lockstep.
    'replace_text "Новое показание" "000013"': 'replace_text "Новое показание" "00013"',
    "assert r==('v13-note','000013'), r": "assert r==('v13-note','00013'), r",
    "assert ('TOTAL','000013') in items, items": "assert ('TOTAL','00013') in items, items",
}
for old, new in replacements.items():
    if old not in src:
        raise SystemExit(f"Expected compatibility marker missing: {old}")
    src = src.replace(old, new)

Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
