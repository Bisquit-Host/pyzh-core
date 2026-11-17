#!/usr/bin/env bash
# Downloads pinned upstream server jars for cross-fork comparison into the ignored build directory.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

fetch() {
    local name="$1" url="$2" expected="$3" destination="$work_root/upstream/$1"
    mkdir -p "$work_root/upstream"
    if [[ ! -s "$destination" ]]; then
        curl --fail --location --silent --show-error -A "perf-harness" "$url" -o "$destination.tmp"
        mv "$destination.tmp" "$destination"
    fi
    local actual
    if command -v sha256sum >/dev/null; then actual="$(sha256sum "$destination" | cut -d' ' -f1)"; else actual="$(shasum -a 256 "$destination" | cut -d' ' -f1)"; fi
    [[ "$actual" == "$expected" ]] || { echo "checksum mismatch for $name" >&2; exit 1; }
    echo "$destination"
}

fetch paper-26.2-135.jar \
    https://fill-data.papermc.io/v1/objects/ef3dbc68b013deafdbbee3bebf1ba351671ebcda94a8f136809b7532d5ba2fe1/paper-26.2-135.jar \
    ef3dbc68b013deafdbbee3bebf1ba351671ebcda94a8f136809b7532d5ba2fe1
fetch leaf-26.2-131.jar \
    https://api.leafmc.one/v2/projects/leaf/versions/26.2/builds/131/downloads/leaf-26.2-131.jar \
    b9a2d120d20b6587469ac18ef668c13dabf9aa33721d0ec27e8d28caf63a733c
