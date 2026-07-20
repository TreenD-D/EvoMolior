# Security Policy

## Reporting A Vulnerability

Please use GitHub's private vulnerability reporting feature for this repository. If private
reporting is unavailable, contact the repository owner privately through their GitHub profile
before opening any public issue.

Do not disclose an exploitable vulnerability publicly until a fix or coordinated disclosure plan
is available.

Include:

- affected revision or release;
- impact and attacker prerequisites;
- reproduction steps or a minimal proof of concept;
- relevant logs with secrets and personal data removed;
- suggested mitigation, if known.

## Supported Versions

Security fixes target the current default branch and the latest published release. Older
development snapshots may not receive backports.

## Security Boundaries

EvoMolior processes model output, files, network sources, and generated tools. Operators should:

- keep provider keys in environment variables or ignored local configuration;
- run the remote dashboard behind HTTPS with strong credentials;
- restrict inbound network access and persistent-volume permissions;
- review generated tools before expanding their execution capabilities;
- avoid treating model output as trusted code or factual truth;
- keep production Firebase, app-store, registry, and cloud credentials outside the repository.

The normal production agent path currently uses in-process role agents with constitutional
interception, group-isolated databases, source-egress controls, and bounded tool subprocesses.
Shuru microVM execution is an optional stronger branch, not the default production boundary.
