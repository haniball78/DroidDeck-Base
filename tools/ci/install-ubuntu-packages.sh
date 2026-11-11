#!/usr/bin/env bash
set -euo pipefail
missing=()
for package in "$@"; do
  if [ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)" != 'install ok installed' ]; then
    missing+=("$package")
  fi
done
if [ "${#missing[@]}" = 0 ]; then
  echo "Ubuntu build dependencies are already installed."
  exit 0
fi
options=(
  -o Dir::Etc::sourcelist=sources.list.d/ubuntu.sources
  -o Dir::Etc::sourceparts=-
  -o Acquire::Retries=3
  -o Acquire::http::Timeout=60
  -o Acquire::https::Timeout=60
  -o DPkg::Lock::Timeout=30
)
install_packages() {
  echo "Updating Ubuntu package indexes."
  sudo timeout --kill-after=10s 300s apt-get "${options[@]}" -o APT::Update::Error-Mode=any update -q || return $?
  echo "Installing: ${missing[*]}"
  sudo timeout --kill-after=10s 600s env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get "${options[@]}" install -y -q --no-install-recommends "${missing[@]}"
}

# The runner's default resolver cannot resolve *.ports.ubuntu.com at all
# (run #71: all five mirrors failed with "Temporary failure resolving",
# 25 min burned). More mirrors under the same broken zone do not help;
# the fix must address DNS itself, and a mirror that does not resolve
# must be skipped in seconds, not after a 300 s apt timeout.
mirror_host() {
  local url="${1#*://}"
  printf '%s' "${url%%/*}"
}
resolves() {
  timeout 10s getent hosts "$1" >/dev/null 2>&1
}
dns_switched=0
try_public_dns() {
  if [ "$dns_switched" = 1 ]; then
    return 1
  fi
  dns_switched=1
  local resolv="${RESOLV_CONF:-/etc/resolv.conf}"
  echo "Runner DNS cannot resolve Ubuntu mirrors; switching to public resolvers."
  if sudo rm -f "$resolv" 2>/dev/null; then
    if printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' | sudo tee "$resolv" >/dev/null 2>&1; then
      return 0
    fi
  fi
  return 1
}

# First attempt: the runner's default sources, but only when its host
# resolves; otherwise fix DNS once and re-test before apt runs.
default_uri="$(sed -nE 's/^URIs:[[:space:]]*//p' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null | head -n1 || true)"
default_host=""
if [ -n "$default_uri" ]; then
  default_host="$(mirror_host "$default_uri")"
fi
default_ok=1
if [ -n "$default_host" ] && ! resolves "$default_host"; then
  default_ok=0
  if try_public_dns && resolves "$default_host"; then
    echo "Public resolvers fixed DNS for $default_host."
    default_ok=1
  else
    echo "::warning::Runner DNS cannot resolve $default_host even with public resolvers; trying other mirrors."
  fi
fi
if [ "$default_ok" = 1 ] && install_packages; then
  exit 0
fi
echo "::warning::Runner mirror failed or timed out; retrying with other Ubuntu mirrors."
sources=$(mktemp --suffix=.sources)
trap 'rm -f "$sources"' EXIT
mirrors=(https://archive.ubuntu.com/ubuntu)
case "$(dpkg --print-architecture)" in
  arm64) mirrors=(
    https://ports.ubuntu.com/ubuntu-ports
    https://us.ports.ubuntu.com/ubuntu-ports
    https://gb.ports.ubuntu.com/ubuntu-ports
    https://de.ports.ubuntu.com/ubuntu-ports
    # Different domains: the runner's resolver fails the whole
    # *.ports.ubuntu.com zone, so these are the fallback that can
    # actually resolve. Verified 2026-11-11: each serves noble InRelease.
    https://mirror.leaseweb.net/ubuntu-ports
    https://mirrors.mit.edu/ubuntu-ports
    http://mirrors.ocf.berkeley.edu/ubuntu-ports
  ) ;;
esac
for mirror in "${mirrors[@]}"; do
  if ! resolves "$(mirror_host "$mirror")"; then
    echo "::warning::Mirror $mirror does not resolve; skipping."
    continue
  fi
  sed -E "s@^URIs:.*@URIs: $mirror@" /etc/apt/sources.list.d/ubuntu.sources > "$sources"
  chmod 644 "$sources"
  options[1]="Dir::Etc::sourcelist=$sources"
  if install_packages; then
    exit 0
  fi
  echo "::warning::Mirror $mirror failed; trying the next mirror."
done
exit 1
