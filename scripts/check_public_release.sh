#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

failures=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

pass() {
  printf 'PASS: %s\n' "$1"
}

if [[ -f LICENSE ]] && grep -q 'GNU AFFERO GENERAL PUBLIC LICENSE' LICENSE; then
  pass "root AGPL license is present"
else
  fail "root LICENSE is missing or not the expected AGPL text"
fi

required_public_files=(
  .dockerignore
  .github/ISSUE_TEMPLATE/bug_report.yml
  .gitignore
  CONTRIBUTING.md
  LICENSE
  README.MD
  SECURITY.md
  scripts/check_public_release.sh
  documentation/README.md
  documentation/EvoMolior_Alignment_Plan.md
  documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html
  documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan_v1.1_agentsdiff.html
  documentation/EvoMolior_Current_Architecture_Deep_Dive.md
  documentation/EvoMolior_Gap_Matrix.md
  documentation/EvoMolior_Implementation_Plan_v1.0.md
  documentation/EvoMolior_Roadmap_and_Project_State.md
  documentation/EvoMolior_Specification_v0.2.html
  documentation/benchmarks/EvoMolior_Benchmark_Ladder.md
  documentation/deployment/Remote_CLI_Dashboard_Deployment.md
  documentation/opensource/Open_Source_Readiness.md
  scripts/check_repository_hygiene.sh
  scripts/test_repository_hygiene.sh
)

missing_public_files=""
for path in "${required_public_files[@]}"; do
  if ! git ls-files --error-unmatch -- "$path" >/dev/null 2>&1; then
    missing_public_files+="${path}"$'\n'
  fi
done

if [[ -n "$missing_public_files" ]]; then
  fail "required public-release files are absent from the Git index"
  printf '%s' "$missing_public_files" | sed -n '1,40p' >&2
else
  pass "required public-release files are tracked"
fi

if git diff --check && git diff --cached --check; then
  pass "working-tree and staged whitespace checks"
else
  fail "whitespace errors exist"
fi

candidate_file="$(mktemp)"
trap 'rm -f "$candidate_file"' EXIT
git diff --cached --name-only --diff-filter=ACMR > "$candidate_file"

if [[ ! -s "$candidate_file" ]]; then
  printf 'WARN: the index has no added/modified release files; staged-surface checks are limited.\n'
fi

staged_internal=""
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  case "$path" in
    documentation/agent-notes/*|documentation/development/*|documentation/logs/*|documentation/tmp/*|documentation/stale/*|documentation/temp_presub.md|documentation/Remediation_plan_after_v72.md)
      staged_internal+="${path}"$'\n'
      ;;
    documentation/prompts/*|documentation/examples/*)
      staged_internal+="${path}"$'\n'
      ;;
    build/*|*/build/*|.evomolior-locks/*|.evomolior-tmp/*|.evomolior-gradle-home/*|.gradle-user/*|.gradle-tmp/*|.system-cache-relief/*|.test-tmp/*|.java-tmp/*)
      staged_internal+="${path}"$'\n'
      ;;
  esac
done < "$candidate_file"

if [[ -n "$staged_internal" ]]; then
  fail "internal/generated paths are staged; curate the release index"
  printf '%s' "$staged_internal" | sed -n '1,30p' >&2
fi

if bash scripts/check_repository_hygiene.sh; then
  pass "repository ignore and size policy"
else
  fail "repository ignore or size policy failed"
fi

tracked_runtime_state="$(
  git ls-files |
    grep -Ei '(^|/)(results|runs|output|outputs)/|(\.db(-wal|-shm)?|\.sqlite3?|\.hprof|\.py[co])$' ||
    true
)"
if [[ -n "$tracked_runtime_state" ]]; then
  fail "generated run/database/cache artifacts remain tracked"
  printf '%s\n' "$tracked_runtime_state" | sed -n '1,30p' >&2
else
  pass "no generated run/database/cache artifacts are tracked"
fi

sensitive_names="$(
  git ls-files |
    grep -Ei '(^|/)(local\.properties|\.env(\..*)?|auth\.json|credentials?\.json|.*\.(jks|p12|p8|pem))$' ||
    true
)"
if [[ -n "$sensitive_names" ]]; then
  fail "sensitive-looking tracked filenames found:\n$sensitive_names"
else
  pass "no sensitive credential filenames are tracked"
fi

generated_logs="$(git ls-files | grep -E '(^|/)(hs_err_pid[0-9]+|replay_pid[0-9]+)\.log$' || true)"
if [[ -n "$generated_logs" ]]; then
  fail "generated JVM crash/replay logs remain tracked"
  printf '%s\n' "$generated_logs" >&2
else
  pass "no generated JVM crash/replay logs are tracked"
fi

public_surface=(
  README.MD
  CONTRIBUTING.md
  SECURITY.md
  documentation/README.md
  documentation/EvoMolior_Alignment_Plan.md
  documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html
  documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan_v1.1_agentsdiff.html
  documentation/EvoMolior_Current_Architecture_Deep_Dive.md
  documentation/EvoMolior_General_Purpose_Improvement_Plan.md
  documentation/EvoMolior_Gap_Matrix.md
  documentation/EvoMolior_Implementation_Plan_v1.0.md
  documentation/EvoMolior_Roadmap_and_Project_State.md
  documentation/EvoMolior_Specification_v0.2.html
  documentation/benchmarks
  documentation/deployment
  documentation/configuration
  documentation/opensource
)

scan_status=0
rg --no-ignore -l '/Volumes/|/Users/|sandbox:/mnt/data|BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY' "${public_surface[@]}" || scan_status=$?
if [[ "$scan_status" -eq 0 ]]; then
  fail "local absolute paths, sandbox links, or private-key blocks exist in the public surface"
elif [[ "$scan_status" -ne 1 ]]; then
  fail "public documentation scan could not complete"
else
  pass "public docs contain no local absolute paths, sandbox links, or private-key blocks"
fi

scan_status=0
git grep -l -I -E --untracked --exclude-standard \
  -e '(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{32,}' \
  -e 'AIza[0-9A-Za-z_-]{20,}' \
  -e 'AKIA[0-9A-Z]{16}' \
  -e 'BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY' \
  -- . || scan_status=$?
if [[ "$scan_status" -eq 0 ]]; then
  fail "a secret-like value was found; inspect every match"
elif [[ "$scan_status" -ne 1 ]]; then
  fail "public source secret scan could not complete"
else
  pass "no high-confidence provider key pattern found in the current public source scan"
fi

if [[ "$failures" -ne 0 ]]; then
  printf '\nPublic release gate failed with %d issue(s).\n' "$failures" >&2
  exit 1
fi

printf '\nPublic release gate passed. Run a full-history scanner and fresh-clone build before publishing.\n'
