#!/usr/bin/env bash
# Compares two result directories written by run.sh or ab.sh. Exits 3 when they differ significantly.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ $# -ne 2 ]]; then
    echo "usage: compare.sh DIR_A DIR_B" >&2
    exit 2
fi
tool_cp="$(find "$work_root/dist" -name 'gson-*.jar' | head -n 1)"
[[ -n "$tool_cp" ]] || { echo "no unpacked server under $work_root/dist; run run.sh first" >&2; exit 1; }
tool Stats compare "$1" "$2"
