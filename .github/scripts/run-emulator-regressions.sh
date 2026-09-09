#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="$GITHUB_WORKSPACE/emulator-regression-logs"
mkdir -p "$LOG_DIR"

run_stage() {
  local name="$1"
  local script="$2"
  local log="$LOG_DIR/${name}.log"
  echo "===== START $name ====="
  set +e
  bash "$script" > >(tee "$log") 2> >(tee -a "$log" >&2)
  local status=$?
  set -e
  if (( status != 0 )); then
    echo "===== FAIL $name (exit $status) =====" | tee -a "$log" >&2
    exit "$status"
  fi
  echo "===== PASS $name =====" | tee -a "$log"
}

# Run the v1.0 upgrade fixture first on v2.0. Its source ZIP is a connector-issued,
# short-lived URL, so the content is SHA-256 pinned and must be consumed immediately
# after the emulator becomes ready. This changes CI-only orchestration, never app code.
if [[ "$GITHUB_REF_NAME" == "ci-fix-v2.0" ]]; then
  run_stage "00-v20-upgrade-v10" "$GITHUB_WORKSPACE/.github/scripts/android-v20-upgrade-v10.sh"
fi
run_stage "01-preview-v13-compat" "$GITHUB_WORKSPACE/.github/scripts/android-preview-v13-compat.sh"
if [[ "$GITHUB_REF_NAME" == "ci-fix-v2.0" ]]; then
  run_stage "02-v20-kill-resume" "$GITHUB_WORKSPACE/.github/scripts/android-v20-kill-resume.sh"
fi
run_stage "03-v11-v14-compat" "$GITHUB_WORKSPACE/.github/scripts/android-v11-v14-compat.sh"
if [[ "$GITHUB_REF_NAME" == "ci-fix-v2.0" ]]; then
  run_stage "04-v20-shortcut" "$GITHUB_WORKSPACE/.github/scripts/android-v20-shortcut.sh"
fi
run_stage "05-v12-db-check" "$GITHUB_WORKSPACE/.github/scripts/android-v12-db-check.sh"
run_stage "06-v14-backup-restore" "$GITHUB_WORKSPACE/.github/scripts/android-v14-backup-restore-compat.sh"
run_stage "07-v14-fault-regression" "$GITHUB_WORKSPACE/.github/scripts/android-v14-fault-regression-compat.sh"
run_stage "08-v14-app-lock" "$GITHUB_WORKSPACE/.github/scripts/android-v14-app-lock.sh"
run_stage "09-v14-enospc" "$GITHUB_WORKSPACE/.github/scripts/android-v14-enospc.sh"
run_stage "10-v14-kill-oversize" "$GITHUB_WORKSPACE/.github/scripts/android-v14-kill-oversize.sh"
if [[ "$GITHUB_REF_NAME" == "ci-fix-v2.0" ]]; then
  run_stage "11-v20-visual-edge" "$GITHUB_WORKSPACE/.github/scripts/android-v20-visual-edge.sh"
  run_stage "12-v20-upgrade-v14" "$GITHUB_WORKSPACE/.github/scripts/android-v20-upgrade-v14.sh"
fi
