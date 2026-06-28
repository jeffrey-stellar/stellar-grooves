#!/usr/bin/env bash
#
# Bootstrap an Ubuntu host (e.g. AWS Lightsail) to run the Stellar Grooves demo.
#
# Installs Docker Engine + the Compose plugin from Docker's official apt repo
# (current versions) and lets the default login user run docker without sudo.
# Idempotent: safe to re-run.
#
# Two ways to use it:
#   * Paste the contents into the Lightsail instance "launch script" box when you
#     create the instance (it runs as root on first boot), OR
#   * copy it to the box and run:  sudo bash bootstrap-host.sh
#
# After it runs, log out/in (or `newgrp docker`) so the group change applies.
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
fi

systemctl enable --now docker

# Let the login user (ubuntu on Lightsail Ubuntu images) use docker without sudo.
TARGET_USER="${SUDO_USER:-ubuntu}"
if id "$TARGET_USER" >/dev/null 2>&1; then
  usermod -aG docker "$TARGET_USER" || true
fi

echo "Docker ready:  $(docker --version)"
echo "Compose ready: $(docker compose version | head -1)"
echo "If you were just added to the docker group, log out/in (or run: newgrp docker)."
