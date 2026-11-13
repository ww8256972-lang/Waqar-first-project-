#!/usr/bin/env bash
set -euo pipefail

# Seeds sample OpenFGA tuples and verifies baseline relation checks.
# Usage:
#   export FGA_STORE_ID="<store_id>"
#   export FGA_API_URL="${FGA_API_URL:-http://localhost:8080}"
#   ./authz/seed-tuples.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${FGA_STORE_ID:-}" ]]; then
  echo "ERROR: FGA_STORE_ID is not set." >&2
  echo "Create a store and upload model.fga first:" >&2
  echo "  fga store create --name institute" >&2
  echo "  export FGA_STORE_ID=<store_id>" >&2
  echo "  fga model write --store-id=\"\$FGA_STORE_ID\" --file=\"$SCRIPT_DIR/model.fga\"" >&2
  exit 1
fi

run_fga() {
  if command -v fga >/dev/null 2>&1; then
    fga "$@"
  elif command -v docker >/dev/null 2>&1; then
    docker run --rm --network host \
      -e FGA_API_URL="${FGA_API_URL:-http://localhost:8080}" \
      -e FGA_API_TOKEN="${FGA_API_TOKEN:-}" \
      -v "$SCRIPT_DIR:/w" -w /w \
      openfga/cli:v0.8.2 "$@"
  else
    echo "ERROR: Neither 'fga' CLI nor 'docker' is installed." >&2
    exit 1
  fi
}

# Institute and admin
run_fga tuple write --store-id="$FGA_STORE_ID" user:anita admin institute:main

# Batch, teacher, student
run_fga tuple write --store-id="$FGA_STORE_ID" institute:main institute batch:cpp2026
run_fga tuple write --store-id="$FGA_STORE_ID" user:teacher_01 teacher batch:cpp2026
run_fga tuple write --store-id="$FGA_STORE_ID" user:rahul student batch:cpp2026

# Student profile + fee record
run_fga tuple write --store-id="$FGA_STORE_ID" institute:main institute student_profile:rahul
run_fga tuple write --store-id="$FGA_STORE_ID" batch:cpp2026 batch student_profile:rahul
run_fga tuple write --store-id="$FGA_STORE_ID" user:rahul owner student_profile:rahul
run_fga tuple write --store-id="$FGA_STORE_ID" user:rahul_dad guardian student_profile:rahul
run_fga tuple write --store-id="$FGA_STORE_ID" institute:main institute fee_record:rahul_nov
run_fga tuple write --store-id="$FGA_STORE_ID" student_profile:rahul profile fee_record:rahul_nov

# Verification checks (expected: allowed: true, then allowed: false)
run_fga query check --store-id="$FGA_STORE_ID" user:teacher_01 can_mark_attendance batch:cpp2026
run_fga query check --store-id="$FGA_STORE_ID" user:teacher_01 can_view fee_record:rahul_nov
