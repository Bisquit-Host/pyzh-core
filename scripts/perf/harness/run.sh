#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ $# -lt 2 ]]; then
    echo "usage: run.sh JAR OUTDIR [options]" >&2
    echo "$usage_common" >&2
    exit 2
fi
jar="$(abs_file "$1")"
out="$(abs_path "$2")"
shift 2
runs=1
parse_common "$@"
acquire_lock
prepare_jar "$jar"
ensure_template "$jar" "$config"

failed=0
for ((run = first_run; run < first_run + runs; run++)); do
    for scenario in "${scenarios[@]}"; do
        run_one "$jar" "$config" "$out" "$scenario" "run-$run" "$quick" 0 || failed=1
    done
done
if [[ "$jfr" == 1 ]]; then
    for scenario in "${scenarios[@]}"; do
        profile_run "$jar" "$out" "$scenario" || failed=1
    done
fi
tool Stats report "$out"
exit "$failed"
