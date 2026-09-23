#!/usr/bin/env bash
set -euo pipefail

# Uses the applied source tree or the pinned Leaf source as the baseline. Requires JDK 25.
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
variant="${1:?Usage: entity-spatial.sh baseline|current OUTPUT_DIR [JMH arguments]}"
output="${2:?Specify an output directory}"
shift 2
mkdir -p "$output"
output="$(cd "$output" && pwd)"
lib="$repo_root/build/perf-libs"
mkdir -p "$lib" "$output/src/org/dreeam/leaf/util" "$output/classes"

fetch() {
    local artifact="$1"
    local destination="$lib/${artifact##*/}"
    if [[ ! -s "$destination" ]]; then
        curl --fail --location --silent --show-error "https://repo.maven.apache.org/maven2/$artifact" -o "$destination.tmp"
        mv "$destination.tmp" "$destination"
    fi
}
fetch org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar
fetch org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar
fetch net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar
fetch org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar
fetch it/unimi/dsi/fastutil/8.5.19/fastutil-8.5.19.jar

leaf_commit="$(sed -n 's/^leafCommit=//p' "$repo_root/gradle.properties")"
for name in KDTree2D KDTree3D PartialSort LeafConstants; do
    relative="org/dreeam/leaf/util/$name.java"
    case "$variant" in
        baseline)
            git -C "$repo_root/.gradle/caches/paperweight/upstreams/leaf" show "$leaf_commit:leaf-server/src/main/java/$relative" > "$output/src/$relative"
            ;;
        current)
            cp "$repo_root/pyzh-server/src/main/java/$relative" "$output/src/$relative"
            ;;
        *) echo "Unknown variant: $variant" >&2; exit 1 ;;
    esac
done
javac -cp "$lib/*" -processorpath "$lib/jmh-core-1.37.jar:$lib/jmh-generator-annprocess-1.37.jar" \
    -d "$output/classes" "$output"/src/org/dreeam/leaf/util/*.java "$repo_root/scripts/perf/EntitySpatialBenchmark.java"
java -cp "$output/classes:$lib/*" org.openjdk.jmh.Main '.*EntitySpatialBenchmark.*' \
    -prof gc -rf json -rff "$output/results.json" "$@" | tee "$output/results.txt"
