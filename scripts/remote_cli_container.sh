#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
if [[ -z "$ACTION" ]]; then
  echo "Usage: $0 build|push|deploy|deploy-direct|release|release-direct" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES="${LOCAL_PROPERTIES_PATH:-$ROOT_DIR/local.properties}"

load_local_properties() {
  [[ -f "$LOCAL_PROPERTIES" ]] || return 0
  while IFS='=' read -r raw_key raw_value || [[ -n "$raw_key" ]]; do
    local key value
    key="$(echo "$raw_key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$key" || "$key" == \#* ]] && continue
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${raw_value:-}"
    value="$(echo "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done < "$LOCAL_PROPERTIES"
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required configuration: $name" >&2
    exit 2
  fi
}

shell_quote() {
  printf "%q" "$1"
}

dotenv_quote() {
  local value="${1:-}"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '"%s"' "$value"
}

write_dotenv_entry() {
  local name="$1"
  local value="${2:-}"
  printf '%s=' "$name"
  dotenv_quote "$value"
  printf '\n'
}

load_local_properties

IMAGE="${EVOMOLIOR_CONTAINER_IMAGE:-evomolior-cli-dashboard}"
TAG="${EVOMOLIOR_CONTAINER_TAG:-latest}"
FULL_IMAGE="${IMAGE}:${TAG}"
REMOTE_DIR="${EVOMOLIOR_REMOTE_DIR:-/opt/evomolior}"
REMOTE_PORT="${EVOMOLIOR_REMOTE_PORT:-22}"
DASHBOARD_PORT="${EVOMOLIOR_DASHBOARD_PORT:-8080}"
REMOTE_BIND_HOST="${EVOMOLIOR_REMOTE_BIND_HOST:-127.0.0.1}"
DASHBOARD_SECURE_COOKIE="${EVOMOLIOR_DASHBOARD_SECURE_COOKIE:-false}"

ssh_args=(-p "$REMOTE_PORT")
scp_args=(-P "$REMOTE_PORT")
if [[ -n "${EVOMOLIOR_REMOTE_SSH_KEY:-}" ]]; then
  ssh_args+=(-i "$EVOMOLIOR_REMOTE_SSH_KEY")
  scp_args+=(-i "$EVOMOLIOR_REMOTE_SSH_KEY")
fi

docker_login_if_configured() {
  if [[ -n "${EVOMOLIOR_REGISTRY_USERNAME:-}" || -n "${EVOMOLIOR_REGISTRY_PASSWORD:-}" ]]; then
    require_var EVOMOLIOR_REGISTRY
    require_var EVOMOLIOR_REGISTRY_USERNAME
    require_var EVOMOLIOR_REGISTRY_PASSWORD
    printf '%s' "$EVOMOLIOR_REGISTRY_PASSWORD" | docker login "$EVOMOLIOR_REGISTRY" \
      --username "$EVOMOLIOR_REGISTRY_USERNAME" \
      --password-stdin
  fi
}

build_image() {
  docker build -t "$FULL_IMAGE" "$ROOT_DIR"
}

build_image_for_platform() {
  local platform="$1"
  docker buildx build --platform "$platform" --load -t "$FULL_IMAGE" "$ROOT_DIR"
}

push_image() {
  docker_login_if_configured
  docker push "$FULL_IMAGE"
}

write_remote_bundle() {
  local bundle_dir="$1"
  mkdir -p "$bundle_dir"
  cat > "$bundle_dir/docker-compose.yml" <<EOF
services:
  evomolior-cli:
    image: \${EVOMOLIOR_IMAGE}
    network_mode: host
    environment:
      EVOMOLIOR_DASHBOARD_HOST: "\${EVOMOLIOR_REMOTE_BIND_HOST:-127.0.0.1}"
      EVOMOLIOR_DASHBOARD_PORT: "\${EVOMOLIOR_DASHBOARD_PORT:-8080}"
      EVOMOLIOR_DASHBOARD_USERNAME: "\${EVOMOLIOR_DASHBOARD_USERNAME:?set an operator username}"
      EVOMOLIOR_DASHBOARD_PASSWORD: "\${EVOMOLIOR_DASHBOARD_PASSWORD:?set an operator password}"
      EVOMOLIOR_DASHBOARD_DATA_DIR: /data
      EVOMOLIOR_DASHBOARD_SECURE_COOKIE: "\${EVOMOLIOR_DASHBOARD_SECURE_COOKIE:-false}"
      QWEN_API_KEY: "\${QWEN_API_KEY:-}"
      OPENROUTER_API_KEY: "\${OPENROUTER_API_KEY:-}"
    volumes:
      - evomolior-data:/data
    restart: unless-stopped

volumes:
  evomolior-data:
EOF

  {
    write_dotenv_entry EVOMOLIOR_IMAGE "$FULL_IMAGE"
    write_dotenv_entry EVOMOLIOR_REMOTE_BIND_HOST "$REMOTE_BIND_HOST"
    write_dotenv_entry EVOMOLIOR_DASHBOARD_PORT "$DASHBOARD_PORT"
    write_dotenv_entry EVOMOLIOR_DASHBOARD_SECURE_COOKIE "$DASHBOARD_SECURE_COOKIE"
    write_dotenv_entry EVOMOLIOR_DASHBOARD_USERNAME "${EVOMOLIOR_DASHBOARD_USERNAME:-}"
    write_dotenv_entry EVOMOLIOR_DASHBOARD_PASSWORD "${EVOMOLIOR_DASHBOARD_PASSWORD:-}"
    write_dotenv_entry QWEN_API_KEY "${QWEN_API_KEY:-}"
    write_dotenv_entry OPENROUTER_API_KEY "${OPENROUTER_API_KEY:-}"
  } > "$bundle_dir/.env"
  chmod 600 "$bundle_dir/.env"
}

remote_docker_login_if_configured() {
  if [[ -n "${EVOMOLIOR_REGISTRY_USERNAME:-}" || -n "${EVOMOLIOR_REGISTRY_PASSWORD:-}" ]]; then
    require_var EVOMOLIOR_REGISTRY
    require_var EVOMOLIOR_REGISTRY_USERNAME
    require_var EVOMOLIOR_REGISTRY_PASSWORD
    local remote="${EVOMOLIOR_REMOTE_USER}@${EVOMOLIOR_REMOTE_HOST}"
    printf '%s' "$EVOMOLIOR_REGISTRY_PASSWORD" | ssh "${ssh_args[@]}" "$remote" \
      "docker login $(shell_quote "$EVOMOLIOR_REGISTRY") --username $(shell_quote "$EVOMOLIOR_REGISTRY_USERNAME") --password-stdin"
  fi
}

require_remote_config() {
  require_var EVOMOLIOR_REMOTE_HOST
  require_var EVOMOLIOR_REMOTE_USER
  require_var EVOMOLIOR_DASHBOARD_USERNAME
  require_var EVOMOLIOR_DASHBOARD_PASSWORD
}

remote_address() {
  printf '%s@%s' "$EVOMOLIOR_REMOTE_USER" "$EVOMOLIOR_REMOTE_HOST"
}

normalize_platform() {
  case "$1" in
    linux/x86_64)
      printf 'linux/amd64'
      ;;
    linux/aarch64)
      printf 'linux/arm64'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

detect_remote_platform() {
  if [[ -n "${EVOMOLIOR_REMOTE_PLATFORM:-}" ]]; then
    normalize_platform "$EVOMOLIOR_REMOTE_PLATFORM"
    return
  fi

  local remote platform
  remote="$(remote_address)"
  platform="$(
    ssh "${ssh_args[@]}" "$remote" \
      "docker version --format '{{.Server.Os}}/{{.Server.Arch}}'"
  )"
  normalize_platform "$platform"
}

local_image_platform() {
  local image_id platform
  image_id="$(local_image_id)"
  if [[ -z "$image_id" ]]; then
    echo "Local image is missing: $FULL_IMAGE" >&2
    exit 2
  fi
  platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image_id")"
  normalize_platform "$platform"
}

local_image_id() {
  docker image ls --no-trunc --format '{{.ID}}' "$FULL_IMAGE" | head -n 1
}

remote_image_id() {
  local remote
  remote="$(remote_address)"
  ssh "${ssh_args[@]}" "$remote" \
    "docker image inspect --format '{{.Id}}' $(shell_quote "$FULL_IMAGE") 2>/dev/null || true"
}

stage_remote_bundle() (
  local remote="${EVOMOLIOR_REMOTE_USER}@${EVOMOLIOR_REMOTE_HOST}"
  local bundle_dir
  bundle_dir="$(mktemp -d)"
  trap 'rm -R -- "$bundle_dir"' EXIT
  write_remote_bundle "$bundle_dir"

  ssh "${ssh_args[@]}" "$remote" "mkdir -p $(shell_quote "$REMOTE_DIR")"
  scp "${scp_args[@]}" "$bundle_dir/docker-compose.yml" "$bundle_dir/.env" "$remote:$REMOTE_DIR/"
  ssh "${ssh_args[@]}" "$remote" "chmod 600 $(shell_quote "$REMOTE_DIR")/.env"
)

start_remote_compose() {
  local pull_policy="$1"
  local remote
  remote="$(remote_address)"
  ssh "${ssh_args[@]}" "$remote" \
    "cd $(shell_quote "$REMOTE_DIR") && docker compose --env-file .env up -d --pull $(shell_quote "$pull_policy") && docker compose ps"
}

deploy_remote() {
  require_remote_config
  stage_remote_bundle
  remote_docker_login_if_configured
  local remote
  remote="$(remote_address)"
  ssh "${ssh_args[@]}" "$remote" \
    "cd $(shell_quote "$REMOTE_DIR") && docker compose --env-file .env pull && docker compose --env-file .env up -d && docker compose ps"
}

deploy_remote_direct() {
  require_remote_config

  local local_id local_platform remote remote_id remote_platform
  remote="$(remote_address)"
  remote_platform="${1:-$(detect_remote_platform)}"
  local_platform="$(local_image_platform)"
  if [[ "$local_platform" != "$remote_platform" ]]; then
    echo "Local image platform $local_platform does not match remote platform $remote_platform." >&2
    echo "Run '$0 release-direct' to build, transfer, and deploy a compatible image." >&2
    exit 2
  fi

  local_id="$(local_image_id)"
  remote_id="$(remote_image_id)"
  if [[ -n "$remote_id" && "$remote_id" == "$local_id" ]]; then
    echo "$FULL_IMAGE is already loaded on $remote; skipping image transfer."
  else
    echo "Streaming $FULL_IMAGE ($local_platform) to $remote without a registry..."
    docker image save "$FULL_IMAGE" | gzip -1 | \
      ssh "${ssh_args[@]}" "$remote" "docker image load"
  fi

  stage_remote_bundle
  start_remote_compose never
}

case "$ACTION" in
  build)
    build_image
    ;;
  push)
    push_image
    ;;
  deploy)
    deploy_remote
    ;;
  deploy-direct)
    deploy_remote_direct
    ;;
  release)
    build_image
    push_image
    deploy_remote
    ;;
  release-direct)
    require_remote_config
    remote_platform="$(detect_remote_platform)"
    echo "Building $FULL_IMAGE for remote platform $remote_platform..."
    build_image_for_platform "$remote_platform"
    deploy_remote_direct "$remote_platform"
    ;;
  *)
    echo "Unknown action: $ACTION" >&2
    echo "Usage: $0 build|push|deploy|deploy-direct|release|release-direct" >&2
    exit 2
    ;;
esac
