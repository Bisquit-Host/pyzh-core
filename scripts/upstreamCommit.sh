#!/usr/bin/env bash

# Commits a Leaf upstream bump with a changelog of the Leaf commits it pulls in.
# Requires curl & jq.
#
# Usage:
#   1. edit leafCommit in gradle.properties to the new Leaf commit
#   2. ./gradlew applyAllPatches && ./gradlew :pyzh-server:applyAllServerPatches
#   3. ./scripts/upstreamCommit.sh
#
# Pass the old hash explicitly if gradle.properties is already committed:
#   ./scripts/upstreamCommit.sh --from <oldLeafCommit>

set -euo pipefail

LEAF_REPO="Winds-Studio/Leaf"

getCommits() {
    curl -sS -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$1/compare/$2...$3" \
        | jq -r '.commits[] | "- \(.sha[:8]) \(.commit.message | split("\n")[0])"'
}

oldHash=""
while [ $# -gt 0 ]; do
    case "$1" in
        --from) oldHash="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Old value from the unstaged diff, new value from the working tree.
if [ -z "$oldHash" ]; then
    oldHash=$(git diff gradle.properties | awk -F= '/^-leafCommit=/{print $2}' | tr -d '[:space:]')
fi
newHash=$(awk -F= '/^leafCommit=/{print $2}' gradle.properties | tr -d '[:space:]')

if [ -z "$newHash" ]; then
    echo "could not read leafCommit from gradle.properties" >&2
    exit 1
fi
if [ -z "$oldHash" ]; then
    echo "no previous leafCommit found; pass --from <hash>" >&2
    exit 1
fi
if [ "$oldHash" = "$newHash" ]; then
    echo "leafCommit unchanged ($newHash); nothing to commit" >&2
    exit 1
fi

echo "Leaf: $oldHash -> $newHash"
changes=$(getCommits "$LEAF_REPO" "$oldHash" "$newHash")

msg=$(mktemp)
{
    echo "Updated Upstream (Leaf)"
    echo
    echo "Upstream has released updates that appear to apply and compile correctly"
    echo
    echo "Leaf Changes:"
    echo "$changes"
} > "$msg"

git add gradle.properties
git commit -F "$msg"
rm -f "$msg"
