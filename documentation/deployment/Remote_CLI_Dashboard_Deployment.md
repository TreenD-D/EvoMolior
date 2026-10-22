# Remote CLI Dashboard Deployment

This deployment wraps the existing EvoMolior CLI distribution in a Docker image and starts an authenticated web dashboard by default. The image still exposes the normal `evomolior` command, so operators can override the container command for direct CLI or benchmark runs.

This guide is the primary runbook for deploying the minimal remote dashboard on a fresh Ubuntu VPS.

## Deployment Model

Recommended production flow:

1. Build the container image on CI or a larger workstation.
2. Push the image to a private registry.
3. Pull the image onto the Ubuntu VPS.
4. Run the dashboard with Docker Compose.
5. Put the dashboard behind HTTPS and restrict inbound access.
6. Mount `/data` to persistent storage and back it up.

This is intentionally different from building directly on a small VPS. The dashboard runtime is lightweight, but the Gradle/Docker build path is not.

### Fast No-Registry Flow

For a one-off deployment or a deadline-driven update, build the image on the workstation and stream
it directly to the VPS over SSH:

```bash
EVOMOLIOR_CONTAINER_IMAGE=evomolior-cli-dashboard \
EVOMOLIOR_CONTAINER_TAG=latest \
EVOMOLIOR_REMOTE_HOST=203.0.113.10 \
EVOMOLIOR_REMOTE_USER=evomolior \
EVOMOLIOR_DASHBOARD_USERNAME=operator \
EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
QWEN_API_KEY="$QWEN_API_KEY" \
./scripts/remote_cli_container.sh release-direct
```

`release-direct`:

1. Queries the remote Docker daemon for its Linux architecture.
2. Uses Docker Buildx to build and load one compatible local image.
3. Compresses and streams `docker image save` over SSH into remote `docker image load`.
4. Copies the Compose bundle and secrets.
5. Starts the service with `docker compose up --pull never`.

No registry login, push, pull, or temporary image archive is required. This is generally the
fastest clean path when the workstation can build the image and the VPS already has Docker and
Docker Compose. The full image crosses the SSH connection on each direct deployment, so a registry
remains better for frequent releases or multiple servers.

The remote platform is detected automatically. To skip detection or override it, set
`EVOMOLIOR_REMOTE_PLATFORM=linux/amd64` or `EVOMOLIOR_REMOTE_PLATFORM=linux/arm64`. This matters when
building on Apple Silicon for a typical AMD64 VPS.

After one compatible image has been built locally, stream and deploy it again without rebuilding:

```bash
./scripts/remote_cli_container.sh deploy-direct
```

If the remote host already has the exact local image ID, `deploy-direct` skips the image transfer
and only refreshes the Compose bundle and service.

## Fresh Ubuntu VPS Setup

These steps assume a fresh Ubuntu 22.04 or 24.04 VPS with SSH access as `root` or as a sudo-capable user.

### 1. Create An Operator User

If you are logged in as `root`, create a non-root deployment user:

```bash
adduser evomolior
usermod -aG sudo evomolior
su - evomolior
```

If your cloud image already created a sudo user, use that user instead.

### 2. Update The OS

```bash
sudo apt-get update
sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl gnupg ufw fail2ban
```

Reboot if the kernel or core packages changed:

```bash
sudo reboot
```

### 3. Add Swap On Small VPS Instances

For a `1 GB` RAM VPS, add swap before running Docker:

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

Swap is not a performance feature. It reduces the chance of immediate OOM kills on small instances.

### 4. Install Docker Engine

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker compose version
```

### 5. Configure Firewall

If you will terminate TLS with a reverse proxy on the same server:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

If this is a private test server and you expose the dashboard directly, open only the dashboard port and restrict it by source IP if possible:

```bash
sudo ufw allow from YOUR_PUBLIC_IP to any port 8080 proto tcp
```

Do not expose unauthenticated Docker APIs or provider keys.

## Build

From the repository root:

```bash
docker build -t evomolior-cli-dashboard .
```

The Dockerfile builds `:engine:cli:installDist` in a JDK 17 Gradle stage with Gradle configuration cache disabled for that distribution task, copies the installed distribution into a JRE 17 runtime image, exposes port `8080`, and stores dashboard state under `/data`.

Prefer building this image in CI or on a larger build host, then pushing it to a registry and pulling it onto a small VPS. During local measurement, Docker accumulated `20.77 GB` of reclaimable build cache and `23.16 GB` of image storage while building/testing the image. That is a poor fit for a `30 GB` root disk if the server is also expected to retain run outputs.

Example registry workflow:

```bash
docker build -t registry.example.com/evomolior/cli-dashboard:latest .
docker push registry.example.com/evomolior/cli-dashboard:latest
```

On the VPS:

```bash
docker login registry.example.com
docker pull registry.example.com/evomolior/cli-dashboard:latest
```

## Build, Push, And Deploy With Gradle

The repository includes a deployment script at `scripts/remote_cli_container.sh` and root Gradle wrappers around it:

```bash
./gradlew buildCliContainer
./gradlew pushCliContainer
./gradlew deployCliContainer
./gradlew deployDirectCliContainer
./gradlew releaseCliContainer
./gradlew releaseDirectCliContainer
```

Task behavior:

- `buildCliContainer`: runs `docker build` for the CLI dashboard image.
- `pushCliContainer`: logs in to the registry if registry credentials are configured, then pushes the image.
- `deployCliContainer`: copies a Compose bundle to the remote server over SSH, pulls the configured image, and starts/restarts the dashboard.
- `deployDirectCliContainer`: streams an existing compatible local image over SSH and starts/restarts the dashboard without pulling.
- `releaseCliContainer`: build, push, and deploy in one command.
- `releaseDirectCliContainer`: detect the remote platform, build locally, stream over SSH, and deploy without a registry.

Configuration can come from environment variables or from local `local.properties`. Environment variables win over `local.properties`. Do not commit `local.properties`.

Example `local.properties`:

```properties
EVOMOLIOR_CONTAINER_IMAGE=registry.example.com/evomolior/cli-dashboard
EVOMOLIOR_CONTAINER_TAG=latest

EVOMOLIOR_REGISTRY=registry.example.com
EVOMOLIOR_REGISTRY_USERNAME=registry-user
EVOMOLIOR_REGISTRY_PASSWORD=registry-password-or-token

EVOMOLIOR_REMOTE_HOST=203.0.113.10
EVOMOLIOR_REMOTE_USER=evomolior
EVOMOLIOR_REMOTE_PORT=22
EVOMOLIOR_REMOTE_DIR=/opt/evomolior
EVOMOLIOR_REMOTE_SSH_KEY=<absolute-path-to-private-key>
EVOMOLIOR_REMOTE_BIND_HOST=127.0.0.1
# Optional override; otherwise detected from the remote Docker daemon.
EVOMOLIOR_REMOTE_PLATFORM=linux/amd64

EVOMOLIOR_DASHBOARD_PORT=8080
EVOMOLIOR_DASHBOARD_USERNAME=operator
EVOMOLIOR_DASHBOARD_PASSWORD=replace-with-a-long-random-password

QWEN_API_KEY=your-qwen-key
OPENROUTER_API_KEY=your-openrouter-key
```

Only these are required for a local image build:

```properties
EVOMOLIOR_CONTAINER_IMAGE=registry.example.com/evomolior/cli-dashboard
EVOMOLIOR_CONTAINER_TAG=latest
```

Required for push to a private registry:

```properties
EVOMOLIOR_REGISTRY=registry.example.com
EVOMOLIOR_REGISTRY_USERNAME=registry-user
EVOMOLIOR_REGISTRY_PASSWORD=registry-password-or-token
```

Required for remote deployment:

```properties
EVOMOLIOR_REMOTE_HOST=203.0.113.10
EVOMOLIOR_REMOTE_USER=evomolior
EVOMOLIOR_DASHBOARD_USERNAME=operator
EVOMOLIOR_DASHBOARD_PASSWORD=replace-with-a-long-random-password
```

The deployment script assumes Docker and Docker Compose are already installed on the remote server. Use the fresh Ubuntu setup steps above before running `deployCliContainer`.

Registry credentials are not required by `deploy-direct` or `release-direct`.

Security notes for the deploy task:

- Registry and provider secrets are read from environment or local-only `local.properties`.
- The generated remote `.env` is copied to `EVOMOLIOR_REMOTE_DIR` on the server.
- Generated dotenv values are quoted and escaped so Compose preserves literal dollar signs,
  quotes, backslashes, tabs, and line breaks in credentials.
- Keep `EVOMOLIOR_REMOTE_BIND_HOST=127.0.0.1` when using Caddy/Nginx/Tailscale/Cloudflare Tunnel.
- If you bind directly to the public interface, restrict the port with firewall rules.

## Run The Dashboard

```bash
docker run --rm --network host \
  -e EVOMOLIOR_DASHBOARD_HOST=127.0.0.1 \
  -e EVOMOLIOR_DASHBOARD_PORT=8080 \
  -e EVOMOLIOR_DASHBOARD_USERNAME=operator \
  -e EVOMOLIOR_DASHBOARD_PASSWORD='change-this-password' \
  -e QWEN_API_KEY="$QWEN_API_KEY" \
  -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  -v evomolior-data:/data \
  evomolior-cli-dashboard
```

Open `http://localhost:8080`, log in with the configured username/password, then launch a normal
run or Tier-0 benchmark. The dashboard records metadata, logs, outputs, and reports in `/data/runs`.
The browser receives authenticated Server-Sent Event snapshots while it is open, with JSON endpoint
fallbacks, so status, progress, metrics, charts, timelines, logs, benchmark results, and artifacts
update without a page reload.

### What The Dashboard Shows

The fleet overview contains total/active/successful/failed run health, terminal success rate,
completed runtime, output footprint, status and workload distribution charts, searchable/filterable
run history, and persisted launch configuration. A run detail view adds:

- backend, runner, fast mode, benchmark slice, lifecycle state, elapsed time, and exit information
- inferred phase/progress and a timestamped lifecycle activity chart and event timeline
- bounded structural live-log tail; unstructured lines and raw model content are withheld
- benchmark aggregates and per-task correctness, score, latency, and failure state without answers
- matched comparative-arm quality/cost/latency cues when a comparative report is present
- separate cost, token, call, and lifecycle charts; absent timestamped telemetry is shown explicitly
- safe artifact inventory, attachment-only downloads, and bounded redacted previews
- warnings for malformed or oversized observability artifacts

The allowlisted metric catalog includes provider cost, calls, input/output/total tokens, latency,
group/agent/generation/round counts, accepted/rejected/staged claims, evidence/citation/claim and
mandatory-node coverage, grades, tool execution/native-use/reuse/simulation metrics, useful exits,
atomic-node completion, group-frontier overlap, redundancy cost, outlier acceptance/adoption,
grader disagreement, and model independence. `results.zip` contributes bounded reads from
`manifest.json`, `cost_report.json`, and `citation_map.json`. A missing metric is shown as
**Unavailable** with provenance when present; the dashboard never manufactures a value.

The routine UI deliberately excludes prompts/objectives, raw LLM content, context windows,
benchmark answers, hidden database/log/authority trees, and sealed competitor-private details.
Internal summaries, benchmark reports, telemetry JSON, transcripts, and result bundles are
preview-only or restricted; generated HTML/SVG is forced to download with a sandbox policy instead
of executing on the dashboard origin. Download authorization is structural: only files below the
CLI's explicit generated `outputs/` subtree are eligible. The server opens exports through a
no-follow descriptor (with file-identity revalidation where secure directory descriptors are not
available), so path validation and transfer do not depend on reopening an attacker-replaced path.

## Docker Compose

Create a deployment directory on the VPS:

```bash
sudo mkdir -p /opt/evomolior
sudo chown "$USER:$USER" /opt/evomolior
cd /opt/evomolior
```

Create `.env`:

```bash
cat > .env <<'EOF'
EVOMOLIOR_IMAGE=registry.example.com/evomolior/cli-dashboard:latest
EVOMOLIOR_DASHBOARD_USERNAME=operator
EVOMOLIOR_DASHBOARD_PASSWORD=replace-with-a-long-random-password
QWEN_API_KEY=
OPENROUTER_API_KEY=
EOF
chmod 600 .env
```

Create `docker-compose.yml`:

```yaml
services:
  evomolior-cli:
    image: ${EVOMOLIOR_IMAGE}
    network_mode: host
    environment:
      EVOMOLIOR_DASHBOARD_HOST: "${EVOMOLIOR_REMOTE_BIND_HOST:-127.0.0.1}"
      EVOMOLIOR_DASHBOARD_PORT: "${EVOMOLIOR_DASHBOARD_PORT:-8080}"
      EVOMOLIOR_DASHBOARD_USERNAME: "${EVOMOLIOR_DASHBOARD_USERNAME:?set an operator username}"
      EVOMOLIOR_DASHBOARD_PASSWORD: "${EVOMOLIOR_DASHBOARD_PASSWORD:?set an operator password}"
      EVOMOLIOR_DASHBOARD_DATA_DIR: /data
      EVOMOLIOR_DASHBOARD_SECURE_COOKIE: "${EVOMOLIOR_DASHBOARD_SECURE_COOKIE:-false}"
      QWEN_API_KEY: "${QWEN_API_KEY:-}"
      OPENROUTER_API_KEY: "${OPENROUTER_API_KEY:-}"
    volumes:
      - evomolior-data:/data
    restart: unless-stopped

volumes:
  evomolior-data:
```

The remote bundle uses Linux host networking so the dashboard process can bind directly to
`127.0.0.1`. This keeps the application on loopback for an SSH tunnel or same-host reverse proxy
and satisfies the dashboard's fail-closed cookie policy. Set `EVOMOLIOR_REMOTE_BIND_HOST=0.0.0.0`
only with HTTPS termination and `EVOMOLIOR_DASHBOARD_SECURE_COOKIE=true`.

### Private SSH Tunnel Access

When the dashboard is bound to the recommended `127.0.0.1`, open a tunnel from the operator
workstation:

```bash
ssh -N -L 8080:127.0.0.1:8080 evomolior@SERVER_ADDRESS
```

Keep that terminal open and browse to `http://127.0.0.1:8080`. The dashboard remains unreachable
on the server's public port while the browser connection travels inside SSH.

Start the service:

```bash
docker compose --env-file .env up -d
docker compose ps
docker compose logs -f
```

The repository also includes a build-oriented compose example at `deployment/remote-cli/docker-compose.yml`. On small VPS instances, prefer either the image-based Compose file above or the
`release-direct` helper so the server does not run the Gradle image build.

## HTTPS Reverse Proxy

For public access, terminate HTTPS in front of the dashboard. Caddy is the simplest option.

Install Caddy:

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | \
  sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | \
  sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update
sudo apt-get install -y caddy
```

Configure `/etc/caddy/Caddyfile`:

```caddyfile
dashboard.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Reload:

```bash
sudo systemctl reload caddy
sudo systemctl status caddy
```

If you do not have a domain, use Tailscale, WireGuard, Cloudflare Tunnel, or source-IP firewall restrictions. Avoid exposing the dashboard over raw HTTP on the public internet.

## Direct CLI Usage

The dashboard is only the default command. Override it to run any CLI command:

```bash
docker run --rm -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" -v evomolior-data:/data evomolior-cli-dashboard evomolior run \
  -prompt "Summarize the provided context" \
  -input /data/input \
  -output /data/manual-output \
  -llm-backend openrouter
```

Benchmarks work the same way:

```bash
docker run --rm -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" -v evomolior-data:/data evomolior-cli-dashboard evomolior benchmark \
  -benchmark frames \
  -limit 2 \
  -output /data/benchmark-output \
  -llm-backend openrouter
```

## Configuration

Required dashboard variables:

- `EVOMOLIOR_DASHBOARD_USERNAME`: operator login username.
- `EVOMOLIOR_DASHBOARD_PASSWORD`: operator login password.

Optional dashboard variables:

- `EVOMOLIOR_DASHBOARD_HOST`: bind host, default `0.0.0.0`.
- `EVOMOLIOR_DASHBOARD_PORT`: bind port, default `8080`.
- `EVOMOLIOR_DASHBOARD_DATA_DIR`: persistent data root, default `/data`.
- `EVOMOLIOR_CLI_BIN`: CLI executable path used by the dashboard subprocess launcher, default `/opt/evomolior/bin/evomolior` in the container.
- `EVOMOLIOR_DASHBOARD_SECURE_COOKIE`: set `true` when the public browser connection uses HTTPS.
- `EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS`: maximum active dashboard subprocesses, default `2`;
  allowed range `1..32`. Launches above capacity receive HTTP `429` instead of creating an
  unbounded process queue.

Provider keys:

- `QWEN_API_KEY`: required for Qwen Cloud runs.
- `OPENROUTER_API_KEY`: required for OpenRouter runs.

Generate a dashboard password with:

```bash
openssl rand -base64 32
```

Provider keys should stay in `.env`, your secret manager, or your container orchestrator secrets. Do not commit real keys to the repository.

## Outputs And Backups

Mount `/data` as a Docker volume or bind mount. Each dashboard run creates a directory under `/data/runs` with `input`, `output`, `run.log`, and `run.json` metadata. Back up this volume to preserve reports and generated artifacts.

For a named Docker volume:

```bash
docker volume inspect evomolior_evomolior-data
docker run --rm -v evomolior_evomolior-data:/data -v "$PWD:/backup" busybox \
  tar czf /backup/evomolior-data-$(date +%Y%m%d-%H%M%S).tar.gz -C /data .
```

To inspect data growth:

```bash
docker compose exec evomolior-cli du -sh /data /data/runs
docker system df
```

To prune unused Docker build cache after confirming no builds are running:

```bash
docker builder prune
docker system prune
```

## Measured Footprint

Measured on Docker `28.5.1` after the `.dockerignore` tightening in this repository:

- Initial build context before `.dockerignore` tightening: `837.43 MB`.
- Build context after `.dockerignore` tightening: `664.04 KB` in the latest validation build.
- Runtime image size from `docker image inspect`: `191.7 MB`.
- Runtime image size shown by `docker images`: `643 MB`.
- Dashboard container memory after startup: `172.8 MiB`.
- Dashboard container memory after login plus one fake dashboard-launched run using `/bin/echo`: `193.2 MiB`.
- Minimal fake run data written under `/data`: `16K`.
- Verified command overrides inside the container: `evomolior run --help` and `evomolior benchmark --help`.

These numbers cover the dashboard shell and fake subprocess path only. Real EvoMolior CLI runs launch an additional JVM subprocess and can use substantially more memory and disk depending on the prompt, benchmark size, debug artifacts, and generated outputs.

For a `2 vCPU / 1 GB RAM / 30 GB` VPS, the recommended deployment pattern is:

1. Build the image in CI or on a larger machine.
2. Push it to a registry and pull it on the VPS, or stream it directly over SSH for a fast one-off release.
3. Run the prebuilt image on the VPS.
4. Add `2-4 GB` swap.
5. Monitor with `docker stats` and `du -sh /data`.
6. Prune Docker periodically with `docker system prune` only after confirming no needed images or stopped containers will be removed.

## Security Notes

The dashboard uses simple form login and a 12-hour HTTP-only, `SameSite=Lax` session cookie. Set
`EVOMOLIOR_DASHBOARD_SECURE_COOKIE=true` behind HTTPS. Put it behind HTTPS on a real server, for
example with Caddy, Nginx, Tailscale, or a Cloudflare Tunnel. Do not expose the dashboard directly
on the public internet without TLS and network access controls.

Operational checklist:

- Use a long random dashboard password.
- Bind the container to `127.0.0.1:8080` when using a reverse proxy.
- Expose only `80/443` publicly when using HTTPS.
- Keep provider API keys out of source control.
- Back up `/data`.
- Monitor memory with `docker stats`.
- Monitor disk with `df -h`, `du -sh /var/lib/docker`, and `docker system df`.
- Rotate dashboard credentials if a server snapshot or `.env` file is shared.

## Runtime Caveats

Generic Docker deployments may not provide the host virtualization features required by Shuru microVM execution. If the host cannot run the full production isolation stack inside the container, use the benchmark modes and fast CLI modes that match the host capability, or run EvoMolior on a host configured for the sandbox dependencies.

## Update Procedure

### Registry Update

On the build machine or CI:

```bash
docker build -t registry.example.com/evomolior/cli-dashboard:latest .
docker push registry.example.com/evomolior/cli-dashboard:latest
```

On the VPS:

```bash
cd /opt/evomolior
docker compose --env-file .env pull
docker compose --env-file .env up -d
docker compose ps
docker compose logs --tail=100 evomolior-cli
```

### No-Registry Update

From the build workstation:

```bash
./scripts/remote_cli_container.sh release-direct
```

This rebuilds for the detected remote platform, transfers the compressed image stream over SSH,
and recreates the service without contacting a registry. Use `deploy-direct` instead when the
correct image is already present in the local Docker image store.

## Troubleshooting

Dashboard does not start:

```bash
docker compose logs --tail=200 evomolior-cli
docker compose config
```

Common causes:

- Missing `EVOMOLIOR_DASHBOARD_USERNAME`.
- Missing `EVOMOLIOR_DASHBOARD_PASSWORD`.
- Port `8080` already in use.
- `/data` volume permissions issue.

Login succeeds but runs fail:

```bash
docker compose exec evomolior-cli env | grep -E 'QWEN|OPENROUTER|EVOMOLIOR'
docker compose exec evomolior-cli ls -la /data/runs
```

Common causes:

- Missing provider API key for the selected backend.
- Host cannot support sandbox/microVM features needed by the selected run mode.
- VPS ran out of memory; check `docker stats`, `dmesg`, and `/var/log/syslog`.

Disk is filling:

```bash
df -h
docker system df
docker compose exec evomolior-cli du -sh /data /data/runs
```

Prune only after backing up important data:

```bash
docker builder prune
docker image prune
```
