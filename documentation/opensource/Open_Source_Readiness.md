# Open Source Readiness Review

This review records the current repository state for public release decisions.

## July 20, 2026 Submission Update

Completed in the submission-preparation pass:

- root `AGPL-3.0-only` license retained;
- root `CONTRIBUTING.md` and `SECURITY.md` added;
- public bug-report issue template added;
- tracked JVM crash/replay logs deleted in the worktree and future crash artifacts ignored; the
  intended deletions still need staging in the curated release commit;
- local acceptance/test/Gradle scratch directories, run outputs, databases, Python caches, and
  internal documentation trees excluded from Git and Docker contexts without deleting local runs;
- tracked iOS Firebase values replaced with unmistakable placeholders;
- README `installDist` versus opt-in `installUserCli` behavior corrected;
- judge-facing Devpost text, layered HTML5 architecture guide, architecture SVG/PNG, thumbnail
  SVG/PNG, demo script, deployment-proof runbook, judge guide, checklist, HTML deck, and PDF deck
  added under `documentation/submission/`;
- narrow Qwen FRAMES evidence exported into a sanitized, path-free public JSON artifact;
- the full benchmark provenance ladder retained under ignored development docs and replaced
  publicly with a sanitized evidence ledger;
- `scripts/check_public_release.sh` added to catch staged internal artifacts, generated logs,
  sensitive filenames, local absolute paths, secret-like values, and malformed SVGs.

Still required before making the repository public:

1. Curate the Git index. The intentionally dirty migration currently stages internal
   `documentation/agent-notes/` and historical development artifacts that should not enter the
   public release commit.
2. Run the new release gate and resolve every failure:

   ```bash
   ./scripts/check_public_release.sh
   ```

3. Run Gitleaks or TruffleHog against the complete branch/history intended for publication.
4. Verify `:engine:cli:installDist` from a fresh clone on JDK 17 after the active acceptance lock is
   released.
5. Make the repository public and confirm GitHub detects the root license.

## Current Verdict

The remote CLI dashboard is ready enough for remote deployment validation and documented operation.

The repository is not ready to publish exactly as the current index stands. The current public
source bytes have an AGPL-3.0-only license, placeholder-only mobile service configuration, and
submission-safe benchmark evidence. The release commit still needs deliberate index curation so
internal notes, generated results, deleted crash logs, and local development state do not enter the
public branch.

## Hackathon Eligibility

The Qwen Cloud Global AI Hackathon rules change the practical recommendation. The submission rules require a public code repository for judging/testing, and state that the repository must be public and open source by including an open source license file that is detectable and visible at the top of the repository page. The Qwen Cloud challenge page also lists GitHub repository, demo video, deck, and project description as submission deliverables, and requires projects to use Qwen Cloud APIs and deploy on Alibaba Cloud infrastructure.

Rule source: `https://qwencloud-hackathon.devpost.com/rules`, Submission Requirements. The key requirement is that the repository be "public and open source" and include a detectable license file.

Because of that, avoid source-available or noncommercial licenses for the hackathon submission. They may express the desired business posture, but they are not standard open-source licenses and create eligibility risk.

Hackathon-compatible recommendation:

1. `AGPL-3.0-only`: best fit for the stated "less permissive for now" preference while still being a real open-source license. It is strong copyleft and covers network/server use, which matches a remote dashboard/SaaS-style deployment.
2. `GPL-3.0-only`: acceptable fallback if AGPL feels too strong or might discourage judges/users, but it does not address hosted-service modifications as directly.
3. `MPL-2.0`: acceptable fallback if you want easier adoption while retaining file-level copyleft, but it is materially more permissive than AGPL/GPL.

Recommended action for Qwen submission: use the root `LICENSE` file with `AGPL-3.0-only`, keep the README license section aligned with `AGPL-3.0-only`, and ensure GitHub detects the license in the repository About panel before submitting.

Do not use for the hackathon if the rules require an open-source license:

- `PolyForm Noncommercial`
- `Business Source License 1.1`
- `Commons Clause`
- `SSPL`
- Any custom "no commercial use" or "source available" terms

These are not safe choices for a rule that requires a public open-source repository.

Minimum hackathon compliance checklist:

- Keep the root `LICENSE` file with a standard OSI-approved license.
- Keep `README.MD` aligned with the selected license, not private/all rights reserved.
- Make the repository public before submission.
- Confirm GitHub detects the license in the repository About panel.
- Keep non-source assets, internal logs, private config, and generated run artifacts out of the public branch.

## Long-Term Project Licensing

If the goal is true OSI-style open source while being less permissive than MIT/Apache, choose a copyleft license:

- `AGPL-3.0-only` or `AGPL-3.0-or-later`: strongest practical open-source option for a server/SaaS-shaped project. Network users must be offered corresponding source for modified versions. Recommended if you want the dashboard/remote-server deployment improvements to stay open when hosted.
- `GPL-3.0-only` or `GPL-3.0-or-later`: strong copyleft for distributed binaries, but it does not close the SaaS/network-use gap as strongly as AGPL.
- `LGPL-3.0-only` or `LGPL-3.0-or-later`: weaker copyleft mainly for libraries. Less aligned with this repo because the value is in the application/system, not only a linkable library.
- `MPL-2.0`: file-level copyleft. Much more business-friendly and less restrictive than GPL/AGPL; a good compromise if you want outside contribution without forcing whole-application copyleft.

If the goal is to prevent commercial use or keep business leverage while showing code, that is not open source in the OSI sense. Use a source-available license instead:

- `PolyForm Noncommercial`: allows noncommercial use, blocks commercial use. Not open source.
- `Business Source License 1.1`: source-available with a future change license date. Not open source until the change date.
- `Commons Clause` added to an open-source license: not open source and often creates compatibility confusion.

Recommendation for "less permissive for now":

1. Use `AGPL-3.0-only` if you want real open source and strong protection for hosted/server modifications. This is now the primary recommendation for the Qwen Cloud hackathon because the rules require a public open-source repository.
2. Use `GPL-3.0-only` if you want strong copyleft without AGPL's network-use obligation.
3. Use `MPL-2.0` if you want a softer file-level copyleft compromise.
4. Use `PolyForm Noncommercial` only after the hackathon, and only if "no commercial use yet" matters more than being open source.
5. Avoid `SSPL` unless you have a specific reason; it is not OSI-approved and can deter contributors and downstream users.

## Public Release Files

Current status:

- `LICENSE`: present as `AGPL-3.0-only`.
- `README.MD`: updated to reference `AGPL-3.0-only`.
- `CONTRIBUTING.md`: present with contribution rules, test expectations, and DCO/CLA stance.
- `SECURITY.md`: present with vulnerability reporting and supported-version guidance.
- Mobile Firebase files: retained as placeholder-only templates because the application expects
  those paths; production values must remain local and ignored.

Add a `NOTICE` only if required by bundled third-party material or the final dependency-license
review.

## Exposure Scan Findings

Commands used:

```bash
git ls-files | grep -Ei '(^|/)(\.env|local\.properties|.*secret.*|.*key.*|.*credential.*|google-services\.json|GoogleService-Info\.plist|keystore|\.jks|\.p12|\.pem|id_rsa|id_ed25519)'
rg -n --hidden --glob '!**/.git/**' --glob '!**/.gradle/**' --glob '!**/build/**' --glob '!results/**' --glob '!documentation/agent-notes/**' --glob '!documentation/tmp/**' --glob '!local.properties' -e 'sk-[A-Za-z0-9_-]{20,}' -e 'BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY' -e '(QWEN_API_KEY|OPENROUTER_API_KEY|DASHSCOPE_API_KEY)\s*=\s*[^<\n$][^\n]+' -e 'AIza[0-9A-Za-z_-]{20,}' -e 'password\s*[:=]\s*["'\''][^"'\'']{8,}' .
```

Findings:

- `local.properties` exists locally and is ignored. Do not commit it.
- `composeApp/google-services.json` is tracked and contains explicit placeholder values only.
- `iosApp/iosApp/GoogleService-Info.plist` is tracked and its former real-looking API/application
  values have been replaced with explicit placeholders.
- No obvious real OpenRouter/Qwen/Dashscope keys were found in the scanned source paths. Matches were examples such as `sk-...`, `sk-test`, or shell snippets that read keys from `local.properties`.
- `fastlane/USAGE.md` now uses a plain P8-content instruction rather than a private-key-shaped
  placeholder block.
- The current `.dockerignore` excludes internal documentation, generated results, mobile sources
  not needed by the CLI image, build outputs, and local secrets. Docker context/build validation
  remains pending because the local Docker daemon is unavailable.

## Repository Hygiene Blockers

The current worktree is heavily dirty. It includes many modified, added, deleted, and untracked files unrelated to the remote dashboard.

Do not publish or tag from this state. Prepare a curated public branch instead.

High-risk categories to review or exclude:

- `documentation/agent-notes/`: internal working notes, benchmark predictions/evaluations, and goal logs.
- `documentation/Remediation_plan_after_v72.md`: local acceptance-packaging input with stale status
  and private evidence references; retain locally but untrack from the public branch.
- `documentation/logs/` and `documentation/tmp/`: execution logs, crash logs, temporary prompts, and run state.
- `results/`: generated run inputs/outputs and benchmark artifacts.
- Mobile service config files: the tracked Firebase plist/json files are placeholder-only templates;
  confirm no production values return during release curation.
- `local.properties`, `.env`, app-store publisher files, keystores, and API keys.
- Dirty deletions in `documentation/`: make sure deleted docs are intentional before public release.

The `.gitignore` has been tightened to ignore future `.env*`, Firebase config files, key material, `documentation/agent-notes/`, `documentation/logs/`, and `results/`. Already tracked files still need explicit removal or replacement in a public-release branch.

## Suggested Public Release Process

1. Create a release branch from a clean base.
2. Cherry-pick only product/source changes intended for public release.
3. Add the chosen `LICENSE`, `CONTRIBUTING.md`, and `SECURITY.md`.
4. Verify the tracked Firebase templates still contain placeholders only.
5. Remove internal notes/logs/results from the public branch.
6. Run a secret scanner such as `gitleaks` or `trufflehog` against the full branch history intended for publication.
7. Run dependency/license reporting for Gradle dependencies and review transitive licenses.
8. Verify:
   - `./gradlew :engine:cli:test`
   - `docker build -t evomolior-cli-dashboard .`
   - dashboard container smoke test
9. Only then push the public branch or make the repository public.

## Dependency License Caveat

This review did not complete a full transitive dependency license audit. The project uses Kotlin, Ktor, Android/Compose, Room/SQLite, Koog, Firebase-related modules, and other JVM libraries. Before public release, generate and review a dependency license report for all included modules, especially if choosing GPL/AGPL, because copyleft compatibility matters.
