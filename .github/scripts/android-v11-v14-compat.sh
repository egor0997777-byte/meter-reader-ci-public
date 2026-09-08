#!/usr/bin/env bash
set -euo pipefail

source_script="$GITHUB_WORKSPACE/.github/scripts/android-v11-flow.sh"
temp_script="$RUNNER_TEMP/android-v11-v14-compat.sh"

python3 - "$source_script" "$temp_script" "$GITHUB_WORKSPACE/meter-reader/app/build.gradle.kts" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()
build_file = Path(sys.argv[3]).read_text()
is_v2 = 'versionCode = 200' in build_file and 'versionName = "2.0.0"' in build_file

# Hardened v1.4 validates configured/default meter digit counts before save.
# Cold water defaults to five integer digits, so the old six-digit 000013
# fixture is invalid. Use the equivalent valid five-digit fixture and keep
# the DB/submission assertions in lockstep.
common = {
    'replace_text "Новое показание" "000013"': 'replace_text "Новое показание" "00013"',
    "assert r==('v13-note','000013'), r": "assert r==('v13-note','00013'), r",
    "assert ('TOTAL','000013') in items, items": "assert ('TOTAL','00013') in items, items",
}
for old, new in common.items():
    if old not in src:
        raise SystemExit(f"Expected compatibility marker missing: {old}")
    src = src.replace(old, new)

if is_v2:
    # v2.0 treats already-valid current-period readings as completed. The dataset
    # created by android-preview has a valid electricity reading but an invalid
    # six-digit cold-water replacement seed, so exactly one point remains.
    # Starting the walk must ask only for that missing cold-water point and then
    # jump directly to the summary instead of re-entering electricity.
    src = src.replace('wait_text "Снять показания"', 'wait_text "Снять показания · осталось 1"', 1)
    src = src.replace('tap_text "Снять показания"', 'tap_text "Снять показания · осталось 1"', 1)

    old_block = '''tap_text "Сохранить и дальше"\nwait_text "Электричество"\n\n# A process restart in the middle of the walk must resume at the next unfinished meter.\nstart_v13\nwait_text "Электричество"\nwait_text "2 из 2"\nshot "14-v13-resume-after-restart.png"\n\n# Finish the multi-tariff meter instead of skipping it so the real submission flow is testable.\nreplace_text "T1 · новое показание" "120"\nhide_ime\nscroll_until "T2 · новое показание"\nreplace_text "T2 · новое показание" "65"\nhide_ime\nscroll_until "T3 · новое показание"\nreplace_text "T3 · новое показание" "30"\nhide_ime\nscroll_until "Сохранить и дальше"\ntap_text "Сохранить и дальше"\nwait_text "Показания готовы"\n'''
    new_block = '''tap_text "Сохранить и дальше"\nwait_text "Показания готовы"\n# Existing valid electricity values from the current period must be reused, not requested again.\nwait_text "T1 110 · T2 55 · T3 22"\n'''
    if old_block not in src:
        raise SystemExit('Expected v1.3 electricity-entry block missing')
    src = src.replace(old_block, new_block, 1)

    # The persisted Submission must contain the reused electricity snapshot.
    src = src.replace("assert ('T1','120') in items and ('T2','65') in items and ('T3','30') in items, items",
                      "assert ('T1','110') in items and ('T2','55') in items and ('T3','22') in items, items", 1)

    # After a completed submission, v2.0 exposes the summary instead of offering
    # an unconditional duplicate-entry action. Keep the large-font smoke test aligned.
    last = 'wait_text "Снять показания"'
    if last not in src:
        raise SystemExit('Expected large-font CTA marker missing')
    src = src.replace(last, 'wait_text "Посмотреть сводку"', 1)
else:
    # v1.4 keeps the old walkthrough behavior but renames the single-address CTA.
    replacements = {
        'wait_text "Снять показания"': 'wait_text "Снять показания заново"',
        'tap_text "Снять показания"': 'tap_text "Снять показания заново"',
    }
    for old, new in replacements.items():
        if old not in src:
            raise SystemExit(f"Expected compatibility marker missing: {old}")
        src = src.replace(old, new)

Path(sys.argv[2]).write_text(src)
PY

bash "$temp_script"
