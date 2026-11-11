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
if install_packages; then
  exit 0
fi
echo "::warning::Runner mirror failed or timed out; retrying with other Ubuntu mirrors."
sources=$(mktemp --suffix=.sources)
trap 'rm -f "$sources"' EXIT
# The runner can fail to resolve ports.ubuntu.com entirely (DNS), so a
# longer timeout does not help: try several mirrors in turn until one
# resolves. Each is a different hostname, so a DNS failure for one does
# not doom the others.
mirrors=(https://archive.ubuntu.com/ubuntu)
case "$(dpkg --print-architecture)" in
  arm64) mirrors=(https://ports.ubuntu.com/ubuntu-ports https://us.ports.ubuntu.com/ubuntu-ports https://gb.ports.ubuntu.com/ubuntu-ports https://de.ports.ubuntu.com/ubuntu-ports) ;;
esac
for mirror in "${mirrors[@]}"; do
  sed -E "s@^URIs:.*@URIs: $mirror@" /etc/apt/sources.list.d/ubuntu.sources > "$sources"
  chmod 644 "$sources"
  options[1]="Dir::Etc::sourcelist=$sources"
  if install_packages; then
    exit 0
  fi
  echo "::warning::Mirror $mirror failed; trying the next mirror."
done
exit 1
