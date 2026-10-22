# Open Source Readiness Review

This review records repository hygiene and licensing considerations for a public source release.
It does not replace the canonical roadmap or the release checks.

## Current Posture

- The repository carries the `AGPL-3.0-only` license.
- `CONTRIBUTING.md` and `SECURITY.md` define contribution and vulnerability-reporting paths.
- Tracked mobile service configuration is placeholder-only; production values must remain local.
- Provider-backed examples use environment variables and must never include real credentials.
- The working tree contains unrelated changes and must not be published or tagged as one unreviewed
  commit.

## Public-Release Hygiene

Keep these categories out of the public index while preserving local bytes:

- `documentation/agent-notes/`;
- `documentation/development/`;
- `documentation/logs/`, `documentation/tmp/`, and `documentation/stale/`;
- `results/`, `runs/`, `output/`, and `outputs/`;
- `local.properties`, `.env*`, API keys, keystores, certificates, and publisher credentials;
- generated build and daemon state.

Already tracked files still require deliberate index curation. Do not use disk cleanup as a
substitute for selecting the intended release contents.

Before a release:

1. Run `./scripts/check_public_release.sh`.
2. Scan the complete intended branch/history with Gitleaks or TruffleHog.
3. Verify that placeholder Firebase configuration contains no production values.
4. Build from a fresh clone on JDK 17.
5. Review dependency licenses for all included modules.
6. Record any tests or Docker checks that were not run.

## License

`AGPL-3.0-only` is the current repository license and is appropriate for a server-shaped
application when network-use copyleft is desired. If the project later needs a different
distribution posture, evaluate AGPL, GPL, LGPL, or MPL against contributor and deployment goals
before changing `LICENSE`.

Do not introduce custom noncommercial or source-available terms while describing the repository as
open source.

## Exposure Findings

- `local.properties` is local and ignored; do not commit it.
- Tracked Firebase configuration files contain explicit placeholders only.
- No obvious real OpenRouter, Qwen, or DashScope credentials were found in the inspected public
  documentation/configuration paths.
- Qwen Cloud and OpenRouter remain optional provider integrations; their keys belong in environment
  configuration or a secret manager.
- The Docker context excludes internal documentation, generated results, build outputs, and local
  secrets.

## Verification Caveat

This review is a release-hygiene record, not proof that the current dirty worktree is ready to
publish. A curated index, full-history secret scan, dependency-license review, fresh-clone build,
and relevant runtime/container checks remain release responsibilities.
