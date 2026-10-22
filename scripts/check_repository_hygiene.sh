#!/usr/bin/env bash
# Inspect the Git index, so force-added files cannot bypass ignore or size policy.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
max_bytes=$((10 * 1024 * 1024))
failures=0
objects="$(mktemp)"
sizes="$(mktemp)"
trap 'rm -f "$objects" "$sizes"' EXIT

while IFS= read -r -d '' path; do
  printf 'FAIL: ignored path is tracked: %q\n' "$path" >&2
  failures=$((failures + 1))
done < <(git ls-files -ci --exclude-standard -z)

while IFS= read -r -d '' entry; do
  metadata=${entry%%$'\t'*}
  path=${entry#*$'\t'}
  read -r mode object stage <<< "$metadata"
  if [[ "$stage" != 0 ]]; then
    printf 'FAIL: unresolved index entry: %q\n' "$path" >&2
    failures=$((failures + 1))
    continue
  fi
  # A submodule names a commit, not a repository-owned file.
  [[ "$mode" == 160000 ]] && continue
  if [[ "$path" == *$'\n'* || "$path" == *$'\r'* ]]; then
    printf 'FAIL: unsupported line break in tracked path: %q\n' "$path" >&2
    failures=$((failures + 1))
    continue
  fi
  printf '%s %s\n' "$object" "$path" >> "$objects"
done < <(git ls-files --stage -z)

git cat-file --batch-check='%(objectsize) %(rest)' < "$objects" > "$sizes"
while IFS=' ' read -r bytes path; do
  if [[ ! "$bytes" =~ ^[0-9]+$ ]]; then
    printf 'FAIL: cannot inspect indexed object: %s %s\n' "$bytes" "$path" >&2
    failures=$((failures + 1))
  elif (( bytes > max_bytes )); then
    printf 'FAIL: tracked file exceeds 10 MiB (%s bytes): %q\n' "$bytes" "$path" >&2
    failures=$((failures + 1))
  fi
done < "$sizes"

if (( failures > 0 )); then
  printf 'Repository hygiene failed: %d issue(s). Keep generated data outside Git.\n' "$failures" >&2
  exit 1
fi
echo 'PASS: no ignored paths or files over 10 MiB in the Git index.'
