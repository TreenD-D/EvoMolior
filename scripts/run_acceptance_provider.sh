#!/usr/bin/env bash
set -uo pipefail

if [[ "$#" -lt 4 || "$3" != "--" ]]; then
    echo "usage: $0 <provider-log> <exit-marker> -- <launcher> [args...]" >&2
    exit 64
fi

provider_log=$1
exit_marker=$2
shift 3

[[ ! -e "$provider_log" ]] || {
    echo "Refusing to overwrite provider log: $provider_log" >&2
    exit 64
}
[[ ! -e "$exit_marker" ]] || {
    echo "Refusing to overwrite provider exit marker: $exit_marker" >&2
    exit 64
}
[[ -d "$(dirname "$provider_log")" && -d "$(dirname "$exit_marker")" ]] || {
    echo "Provider log and exit-marker parent directories must already exist" >&2
    exit 64
}
provider_log_identity="$(cd "$(dirname "$provider_log")" && pwd -P)/$(basename "$provider_log")"
exit_marker_identity="$(cd "$(dirname "$exit_marker")" && pwd -P)/$(basename "$exit_marker")"
[[ "$provider_log_identity" != "$exit_marker_identity" ]] || {
    echo "Provider log and exit marker must be distinct files" >&2
    exit 64
}

"$@" 2>&1 | tee "$provider_log"
pipeline_status=("${PIPESTATUS[@]}")
provider_exit_code=${pipeline_status[0]}
tee_exit_code=${pipeline_status[1]}

[[ "$provider_exit_code" =~ ^[0-9]+$ ]] || {
    echo "Provider launcher produced an invalid exit status: $provider_exit_code" >&2
    exit 70
}
[[ "$tee_exit_code" -eq 0 ]] || {
    echo "Provider log capture failed with exit status $tee_exit_code" >&2
    exit 74
}

marker_tmp="$exit_marker.tmp.$$"
trap 'rm -f "$marker_tmp"' EXIT
printf '%s\n' "$provider_exit_code" > "$marker_tmp"
mv "$marker_tmp" "$exit_marker"
trap - EXIT
exit "$provider_exit_code"
