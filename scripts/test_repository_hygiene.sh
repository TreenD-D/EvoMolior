#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'find "$fixture" -depth -delete' EXIT
git init -q "$fixture"
cd "$fixture"
cp "$root/.gitignore" .gitignore
git add .gitignore
bash "$root/scripts/check_repository_hygiene.sh"

for path in \
  .gradle-tmp/example .system-cache-relief/example .evomolior-gradle-home/example \
  module/build/example results/example runs/example nested/outputs/example \
  weights/model.gguf bundle.zip; do
  git check-ignore -q "$path" || { echo "Not ignored: $path" >&2; exit 1; }
done
if git check-ignore -q gradle/wrapper/gradle-wrapper.jar; then
  echo 'Gradle wrapper must remain trackable' >&2
  exit 1
fi

small="$(git hash-object -w --stdin <<< 'fixture')"
git update-index --add --cacheinfo "100644,$small,.gradle-tmp/forced.txt"
if bash "$root/scripts/check_repository_hygiene.sh"; then
  echo 'Expected forced ignored file to fail' >&2
  exit 1
fi
git update-index --force-remove .gradle-tmp/forced.txt

large="$(dd if=/dev/zero bs=1048576 count=11 2>/dev/null | git hash-object -w --stdin)"
git update-index --add --cacheinfo "100644,$large,large file.bin"
if bash "$root/scripts/check_repository_hygiene.sh"; then
  echo 'Expected oversized file to fail' >&2
  exit 1
fi
git update-index --force-remove 'large file.bin'
git update-index --add --cacheinfo "100644,$small,ordinary file.txt"
bash "$root/scripts/check_repository_hygiene.sh"
echo 'PASS: repository hygiene regression tests.'
