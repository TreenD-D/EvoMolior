#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "usage: $0 <output-manifest>" >&2
    exit 64
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root_dir"

output=$1
[[ -d "$(dirname "$output")" ]] || {
    echo "Manifest parent directory must already exist: $(dirname "$output")" >&2
    exit 64
}
[[ ! -L "$output" ]] || {
    echo "Refusing to replace a symbolic-link manifest: $output" >&2
    exit 64
}

case "$output" in
    /*) output_identity=$output ;;
    *) output_identity="$root_dir/$output" ;;
esac
output_identity="$(cd "$(dirname "$output_identity")" && pwd -P)/$(basename "$output_identity")"

status_file="$(mktemp)"
entries_file="$(mktemp)"
manifest_tmp="$(mktemp "$(dirname "$output_identity")/.authored-source-manifest.XXXXXX")"
trap 'rm -f "$status_file" "$entries_file" "$manifest_tmp"' EXIT

git status --porcelain=v1 -z --untracked-files=all > "$status_file"

is_excluded() {
    case "$1" in
        .git/*|.idea/*|.gradle/*|.gradle-*/*|.evomolior-*/*|.system-cache-relief/*|\
        .test-tmp/*|.java-tmp/*|build/*|*/build/*|*/.gradle/*|*/.kotlin/*|\
        documentation/agent-notes/*|local.properties|*.db|*.db-wal|*.db-shm|*.sqlite|\
        *.sqlite3|*.hprof|*.pyc|*.pyo|hs_err_pid*.log|replay_pid*.log)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

file_size() {
    stat -f '%z' "$1" 2>/dev/null || stat -c '%s' "$1"
}

while IFS= read -r -d '' record <&3; do
    status=${record:0:2}
    path=${record:3}
    if [[ "$status" == *R* || "$status" == *C* ]]; then
        IFS= read -r -d '' path <&3 || {
            echo "Malformed rename/copy record in git status" >&2
            exit 70
        }
    fi
    [[ "$path" != *$'\t'* && "$path" != *$'\n'* ]] || {
        echo "Manifest paths cannot contain tabs or newlines: $path" >&2
        exit 65
    }
    is_excluded "$path" && continue
    candidate_identity="$root_dir/$path"
    [[ "$candidate_identity" != "$output_identity" ]] || continue
    if [[ "$status" == *D* ]]; then
        printf '%s\t-\t0\t%s\n' "$status" "$path" >> "$entries_file"
        continue
    fi
    [[ -f "$path" && ! -L "$path" ]] || {
        echo "Authored manifest candidate is not a regular non-symlink file: $path" >&2
        exit 65
    }
    printf '%s\t%s\t%s\t%s\n' \
        "$status" \
        "$(shasum -a 256 "$path" | awk '{print $1}')" \
        "$(file_size "$path")" \
        "$path" >> "$entries_file"
done 3< "$status_file"

LC_ALL=C sort -o "$entries_file" "$entries_file"
entry_count="$(wc -l < "$entries_file" | tr -d ' ')"
source_tree_clean=true
[[ "$entry_count" -eq 0 ]] || source_tree_clean=false

{
    printf 'schemaVersion\tauthored-source-manifest-v1\n'
    printf 'headCommit\t%s\n' "$(git rev-parse HEAD)"
    printf 'sourceTreeClean\t%s\n' "$source_tree_clean"
    printf 'entryCount\t%s\n' "$entry_count"
    printf 'columns\tstatus\tsha256\tsizeBytes\tpath\n'
    sed 's/^/file\t/' "$entries_file"
} > "$manifest_tmp"

mv "$manifest_tmp" "$output_identity"
trap - EXIT
rm -f "$status_file" "$entries_file"

shasum -a 256 "$output_identity"
