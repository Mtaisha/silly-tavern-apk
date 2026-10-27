#!/usr/bin/env bash
# End-to-end smoke test for both Termux exporter implementations.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEPENDENCY_TREE="${1:-$ROOT/vendor/SillyTavern}"
EXPORTER="$ROOT/tools/termux-export.sh"

for package in archiver yauzl; do
    [ -f "$DEPENDENCY_TREE/node_modules/$package/package.json" ] || {
        echo "missing $package under $DEPENDENCY_TREE/node_modules" >&2
        exit 1
    }
done
command -v node >/dev/null
command -v zip >/dev/null
command -v unzip >/dev/null

WORK="$(mktemp -d "${TMPDIR:-/tmp}/jiuguan-export-e2e.XXXXXX")"
cleanup() {
    case "$(basename "$WORK")" in
        jiuguan-export-e2e.*) rm -rf -- "$WORK" ;;
        *) echo "refusing to remove unexpected test directory: $WORK" >&2 ;;
    esac
}
trap cleanup EXIT

ST="$WORK/SillyTavern"
mkdir -p \
    "$ST/data/default-user/characters" \
    "$ST/data/default-user/chats/fixture" \
    "$ST/plugins/export-fixture" \
    "$ST/public/scripts/extensions/third-party/export-fixture" \
    "$WORK/node-output" \
    "$WORK/zip-output"
printf '%s\n' 'console.log("fixture");' > "$ST/server.js"
printf '%s\n' '{' '  "name": "sillytavern",' '  "version": "1.19.0-test"' '}' > "$ST/package.json"
printf '%s\n' '{"exportFixture":true}' > "$ST/data/default-user/settings.json"
printf '%s\n' '{"name":"Exporter Fixture"}' > "$ST/data/default-user/characters/fixture.json"
printf '%s\n' '{"mes":"payload bytes must survive the archive"}' > "$ST/data/default-user/chats/fixture/chat.jsonl"
printf '%s\n' '{"fixture-key":"not-a-real-secret"}' > "$ST/data/default-user/secrets.json"
printf '%s\n' 'module.exports = { fixture: true };' > "$ST/plugins/export-fixture/index.js"
printf '%s\n' 'export const fixture = true;' > "$ST/public/scripts/extensions/third-party/export-fixture/index.js"
ln -s "$DEPENDENCY_TREE/node_modules" "$ST/node_modules"

verify_archive() {
    local archive="$1"
    unzip -tqq "$archive"
    for expected in \
        data/default-user/settings.json \
        data/default-user/secrets.json \
        plugins/export-fixture/index.js \
        public/scripts/extensions/third-party/export-fixture/index.js; do
        unzip -Z1 "$archive" | grep -Fx "$expected" >/dev/null
    done
    cmp <(unzip -p "$archive" data/default-user/settings.json) "$ST/data/default-user/settings.json"
    cmp <(unzip -p "$archive" data/default-user/chats/fixture/chat.jsonl) "$ST/data/default-user/chats/fixture/chat.jsonl"
}

run_packer() {
    local packer="$1" output="$2" archive
    echo "== $packer packer =="
    JIUGUAN_PACKER="$packer" bash "$EXPORTER" "$ST" -o "$output" -y
    archive="$(find "$output" -maxdepth 1 -type f -name '*.zip' -print -quit)"
    [ -n "$archive" ] || { echo "$packer did not produce an archive" >&2; exit 1; }
    verify_archive "$archive"
    echo "$packer archive: integrity, paths, and payload bytes verified"
}

run_packer node "$WORK/node-output"
run_packer zip "$WORK/zip-output"
