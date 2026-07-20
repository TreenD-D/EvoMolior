# Contributing to EvoMolior

Thanks for helping improve EvoMolior.

## Before You Start

1. Search existing issues and discussions before opening a duplicate.
2. For a substantial behavior or architecture change, open an issue describing the user problem,
   proposed boundary, and verification plan.
3. Never commit API keys, Firebase production configuration, credentials, run-private data, or
   generated benchmark archives.

No contributor license agreement or Developer Certificate of Origin workflow is currently
configured. Contributions are submitted under the repository's `AGPL-3.0-only` license.

## Development Setup

Requirements:

- JDK 17
- Git
- A Qwen Cloud or OpenRouter key only when running provider-backed tests

Build the CLI:

```bash
./gradlew :engine:cli:installDist
```

Run focused engine tests:

```bash
./gradlew --no-daemon --no-configuration-cache \
  :engine:core:test \
  :engine:substrate:test \
  :engine:agents:test \
  :engine:sandbox:test \
  :engine:api:test \
  :engine:orchestration:test \
  :engine:competition:test \
  :engine:cli:test
```

Use module-specific tests while iterating. Provider-backed tests must remain opt-in and must never
print or persist credentials.

## Pull Requests

- Keep changes focused and explain the product behavior they alter.
- Add or update tests for changed contracts.
- Update `README.MD` and maintained architecture documentation when user-visible behavior changes.
- Preserve conservative benchmark language: report ties, regressions, unavailable metrics, and
  failed runs alongside wins.
- Run `git diff --check` before requesting review.
- Call out tests that were not run and explain why.

## Coding Conventions

Repository-wide and module-specific conventions are documented in `AGENTS.md` and each
`evomolior/*/agents.md` file. In particular:

- public Kotlin classes and functions require KDoc;
- asynchronous work uses coroutines;
- Room entities contain no business logic;
- Compose UI strings use resources;
- normal production behavior must be verified through connected call paths, not only scaffolding.

## Reporting Bugs

Include:

- the exact command and JVM version;
- expected and actual behavior;
- the smallest reproducible configuration;
- redacted logs or artifact paths;
- whether the failure occurred before activation, during a round, grading, publication, or archive
  verification.

Do not put security vulnerabilities or credentials in a public issue. Follow `SECURITY.md`.
