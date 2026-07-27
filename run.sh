#!/usr/bin/env bash
#
# One-command launcher for the OurCrowd Press Monitor: checks Docker is installed, then
# builds and starts the full stack (MySQL, Ollama, backend, frontend) via Docker Compose.
#
#   ./run.sh
#
# Then open http://localhost:8080. Equivalent by hand:
#   docker compose up --build
#
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Checking Docker..."
if ! command -v docker >/dev/null 2>&1; then
  echo "!! Docker is not installed. Get it from https://docs.docker.com/get-docker/" >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "!! Docker Compose is not available (need Docker Desktop or the compose plugin)." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "!! Docker is installed but not running. Start Docker and try again." >&2
  exit 1
fi

echo "==> Starting the stack with Docker Compose (this may take a few minutes on first run)..."
docker compose up --build
