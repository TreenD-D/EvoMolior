# Alibaba Cloud Deployment Proof Runbook

Deployment is intentionally left to the operator. This document defines the proof package required
after the parallel code work finishes.

## Recommended Deployment

- Alibaba Cloud ECS instance running Ubuntu 22.04 or 24.04.
- Docker Engine and Docker Compose.
- EvoMolior dashboard container from the repository `Dockerfile`.
- Persistent Docker volume or mounted data disk.
- HTTPS termination or a private judge-access path.
- Qwen Cloud key stored only in server environment configuration.

The generic deployment implementation already exists:

- `Dockerfile`
- `deployment/remote-cli/docker-compose.yml`
- `scripts/remote_cli_container.sh`
- `documentation/deployment/Remote_CLI_Dashboard_Deployment.md`

## Minimal Operator Flow

Configure local-only environment or `local.properties` values:

```text
EVOMOLIOR_CONTAINER_IMAGE=<registry>/<namespace>/evomolior-cli-dashboard
EVOMOLIOR_CONTAINER_TAG=<release-tag>
EVOMOLIOR_REMOTE_HOST=<ecs-public-ip-or-hostname>
EVOMOLIOR_REMOTE_USER=<ssh-user>
EVOMOLIOR_REMOTE_BIND_HOST=127.0.0.1
EVOMOLIOR_DASHBOARD_USERNAME=<operator-user>
EVOMOLIOR_DASHBOARD_PASSWORD=<long-random-password>
QWEN_API_KEY=<secret>
```

Then use the documented release helper:

```bash
./gradlew releaseCliContainer
```

Or build/push/deploy through `scripts/remote_cli_container.sh`.

## Required Proof

Capture at least two screenshots:

### Screenshot A — Alibaba Cloud Infrastructure

Show, in one frame:

- Alibaba Cloud console branding;
- ECS instance name and `Running` state;
- region;
- public IP or hostname, if safe to disclose;
- instance creation/runtime context.

Redact billing identifiers, account email, and unrelated resources.

### Screenshot B — Running EvoMolior Backend

Show, in one frame:

- browser address bar with the deployed host;
- EvoMolior authenticated dashboard;
- one Qwen Cloud run or run detail;
- visible backend/model identity or run metadata;
- successful/terminal status and an artifact or metrics panel.

If the dashboard requires login, provide judges with credentials through Devpost's permitted private
testing-instructions field. Never put credentials in the repository or public video.

## Supporting Terminal Proof

Keep a private or submission-safe terminal capture of:

```bash
docker compose ps
docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' <container>
curl -I https://<deployed-host>/
```

If no container health check is configured, omit the health expression and show running state plus
an authenticated browser response.

## Devpost Wording

Use only after deployment:

> The EvoMolior Ktor dashboard and CLI runtime are deployed in Docker on Alibaba Cloud ECS, with a
> persistent data volume for run metadata and artifacts. Qwen Cloud/DashScope provides the model
> backend. The submission includes an Alibaba Cloud console screenshot and the running dashboard.

Before deployment, do not claim that the backend is live on Alibaba Cloud.

## Final Verification

- open the deployment URL in a logged-out incognito window;
- verify TLS and redirects;
- verify judge credentials;
- launch or inspect a Qwen-backed run;
- verify no secrets appear in browser responses, downloadable artifacts, or screenshots;
- confirm the deployment remains available through the judging period.
