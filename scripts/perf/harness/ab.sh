#!/usr/bin/env bash
# Runs two jars interleaved per scenario so thermal and background drift hit both sides alike, then compares
# them. The order alternates each run (A B, B A, A B, ...): whatever ran just before a measurement leaves the
# machine in a different state, and with a fixed order that shows up as a difference between the jars.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [[ $# -lt 3 ]]; then
    echo "usage: ab.sh JAR_A JAR_B OUTDIR [options]   (results in OUTDIR/A and OUTDIR/B)" >&2
    echo "$usage_common" >&2
    exit 2
fi
jar_a="$(abs_file "$1")"
jar_b="$(abs_file "$2")"
out="$(abs_path "$3")"
shift 3
runs=3
parse_common "$@"
acquire_lock
prepare_jar "$jar_a"
ensure_template "$jar_a" "$config"

failed=0
for ((run = first_run; run < first_run + runs; run++)); do
    if ((run % 2 == 1)); then order="A B"; else order="B A"; fi
    for scenario in "${scenarios[@]}"; do
        for side in $order; do
            if [[ "$side" == A ]]; then jar="$jar_a"; else jar="$jar_b"; fi
            prepare_jar "$jar"
            run_one "$jar" "$config" "$out/$side" "$scenario" "run-$run" "$quick" 0 || failed=1
        done
    done
done
if [[ "$jfr" == 1 ]]; then
    for scenario in "${scenarios[@]}"; do
        prepare_jar "$jar_a"
        profile_run "$jar_a" "$out/A" "$scenario" || failed=1
        prepare_jar "$jar_b"
        profile_run "$jar_b" "$out/B" "$scenario" || failed=1
    done
fi
tool Stats report "$out/A" >/dev/null
tool Stats report "$out/B" >/dev/null
tool Stats compare "$out/A" "$out/B" | tee "$out/compare.txt" || true
exit "$failed"
