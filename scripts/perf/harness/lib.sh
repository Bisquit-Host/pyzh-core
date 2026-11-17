# Shared by run.sh, ab.sh and compare.sh. Requires JDK 25 on PATH.
harness_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$harness_dir/../../.." && pwd)"
work_root="$repo_root/build/perf-harness"

seed=20260216
layout_version=1
all_scenarios="idle-survival mob-farm villagers items-hoppers redstone chunkgen players-moving"
jvm_flags="${HARNESS_JVM_FLAGS:--Xms4G -Xmx4G -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC}"
port="${HARNESS_PORT:-25599}"

sha() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -c1-16; else shasum -a 256 "$1" | cut -c1-16; fi
}

# Unpacks the launcher jar once (server jar, libraries, vanilla cache) and compiles the plugin against it.
# Sets: dist, plugin_jar, tool_cp.
prepare_jar() {
    local jar="$1"
    dist="$work_root/dist/$(sha "$jar")"
    plugin_jar="$dist/harness-plugin.jar"
    if [[ ! -d "$dist/versions" ]]; then
        mkdir -p "$dist"
        (cd "$dist" && java -Dpaperclip.patchonly=true -jar "$jar" >"$dist/patch.log" 2>&1) || { cat "$dist/patch.log" >&2; exit 1; }
    fi
    local classpath
    classpath="$(find "$dist/versions" "$dist/libraries" -name '*.jar' | tr '\n' ':')"
    tool_cp="$(find "$dist/libraries" -name 'gson-*.jar' | head -n 1)"
    local sources="$harness_dir/plugin"
    if [[ ! -s "$plugin_jar" || -n "$(find "$sources" -newer "$plugin_jar" -type f | head -n 1)" ]]; then
        rm -rf "$dist/plugin-classes"
        mkdir -p "$dist/plugin-classes"
        javac -nowarn -proc:none -cp "$classpath" -d "$dist/plugin-classes" "$sources"/src/pyzh/harness/*.java
        cp "$sources/plugin.yml" "$dist/plugin-classes/"
        printf 'paperweight-mappings-namespace: mojang\n' >"$dist/manifest.txt"
        jar --create --file "$plugin_jar" --manifest "$dist/manifest.txt" -C "$dist/plugin-classes" .
    fi
}

# Fresh server directory: unpacked distribution linked in, pinned config, plugin, optional world.
make_server_dir() {
    local dir="$1" config="$2"
    rm -rf "$dir"
    mkdir -p "$dir/plugins"
    ln -s "$dist/versions" "$dist/libraries" "$dist/cache" "$dir/"
    cp -R "$config"/. "$dir/"
    cp "$plugin_jar" "$dir/plugins/"
    {
        grep -v -E '^(server-port|level-seed|online-mode|allow-flight|level-name|management-server-secret)=' "$dir/server.properties" 2>/dev/null || true
        printf 'server-port=%s\nlevel-seed=%s\nonline-mode=false\nallow-flight=true\nlevel-name=world\n' "$port" "$seed"
    } >"$dir/server.properties.new"
    mv "$dir/server.properties.new" "$dir/server.properties"
}

start_server() {
    local dir="$1" jar="$2"
    shift 2
    # shellcheck disable=SC2086
    (cd "$dir" && ${HARNESS_LAUNCH_PREFIX:-} java $jvm_flags -Dcom.mojang.eula.agree=true "$@" -jar "$jar" --nogui </dev/null >"$dir/console.log" 2>&1)
}

# The template world holds only pre-generated terrain. It is built once per seed and layout and copied per run.
ensure_template() {
    local jar="$1" config="$2"
    template="$work_root/template-$seed-v$layout_version"
    [[ -d "$template/world" ]] && return
    echo "generating world template (once) into $template" >&2
    local dir="$work_root/work/pregen"
    make_server_dir "$dir" "$config"
    start_server "$dir" "$jar" -Dharness.mode=pregen
    grep -q HARNESS_PREGEN_DONE "$dir/console.log" || { tail -n 40 "$dir/console.log" >&2; echo "pregen failed" >&2; exit 1; }
    mkdir -p "$template"
    rm -f "$dir/world/session.lock"
    mv "$dir/world" "$template/world"
    grep HARNESS "$dir/console.log" >"$template/pregen.log"
    rm -rf "$dir"
}

# run_one JAR CONFIG OUTDIR SCENARIO LABEL QUICK(0|1) JFR(0|1)
run_one() {
    local jar="$1" config="$2" out="$3/$4/$5" scenario="$4" quick="$6" jfr="$7"
    local dir="$work_root/work/run"
    mkdir -p "$out"
    make_server_dir "$dir" "$config"
    cp -R "$template/world" "$dir/world"
    local args=(-Dharness.mode=run "-Dharness.scenarios=$scenario" "-Dharness.out=$out")
    [[ "$quick" == 1 ]] && args+=(-Dharness.quick=true)
    [[ "$jfr" == 1 ]] && args+=("-Dharness.jfr=$out/profile.jfr" -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:FlightRecorderOptions:stackdepth=512)
    local started=$SECONDS
    start_server "$dir" "$jar" "${args[@]}" || true
    cp "$dir/console.log" "$out/server.log"
    if [[ ! -s "$out/result.json" ]]; then
        tail -n 40 "$out/server.log" >&2
        echo "FAILED: $scenario $5 (see $out/server.log)" >&2
        return 1
    fi
    echo "  $scenario $5: $((SECONDS - started))s, $(grep -o 'mspt_mean=[0-9.]*' "$out/server.log" | tail -n 1)" >&2
    rm -rf "$dir"
}

# Source-launched Java tools; gson comes from the unpacked server libraries.
tool() {
    local name="$1"
    shift
    java -cp "$tool_cp" "$harness_dir/tools/$name.java" "$@"
}

usage_common='  --scenario a,b   comma-separated scenarios, each in its own server (a+b runs them together in one)
  --runs N         measured runs per scenario (run.sh default 1, ab.sh default 3 per jar)
  --first-run N    number the runs from N, to add runs to an existing OUTDIR (default 1)
  --quick          shorter warmup and window
  --jfr            one extra profiled run per scenario (profile.jfr + profile.md, excluded from statistics)
  --config DIR     server config directory to use instead of config/default
Scenarios: '"$all_scenarios"

# Parses the shared options into: scenarios (array), runs, first_run, quick, jfr, config. The caller presets runs.
parse_common() {
    scenarios=()
    first_run=1
    quick=0
    jfr=0
    config="$harness_dir/config/default"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --scenario) IFS=',' read -r -a scenarios <<<"$2"; shift 2 ;;
            --runs) runs="$2"; shift 2 ;;
            --first-run) first_run="$2"; shift 2 ;;
            --quick) quick=1; shift ;;
            --jfr) jfr=1; shift ;;
            --config) config="$(cd "$2" && pwd)"; shift 2 ;;
            *) echo "unknown option: $1" >&2; exit 2 ;;
        esac
    done
    if [[ ${#scenarios[@]} -eq 0 ]]; then
        read -r -a scenarios <<<"$all_scenarios"
    fi
}

abs_path() {
    mkdir -p "$1"
    (cd "$1" && pwd)
}

abs_file() {
    echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
}

# One server at a time on this machine: measured runs must not overlap.
acquire_lock() {
    mkdir -p "$work_root"
    if ! mkdir "$work_root/lock" 2>/dev/null; then
        echo "another harness run holds $work_root/lock (remove it if that run is dead)" >&2
        exit 1
    fi
    trap 'rmdir "$work_root/lock" 2>/dev/null' EXIT
}

profile_run() {
    local jar="$1" out="$2" scenario="$3"
    run_one "$jar" "$config" "$out" "$scenario" run-jfr "$quick" 1 || return 1
    tool JfrReport "$out/$scenario/run-jfr/profile.jfr" >"$out/$scenario/run-jfr/profile.md"
}
