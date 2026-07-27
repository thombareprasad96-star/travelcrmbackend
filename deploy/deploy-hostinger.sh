#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-$(pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.hostinger.yml}"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-travelcrm}"
TRAVELCRM_IMAGE="${TRAVELCRM_IMAGE:-${IMAGE_TAG:-}}"
PRUNE_OLD_IMAGES="${PRUNE_OLD_IMAGES:-true}"

cd "${APP_DIR}"

if [ -z "${TRAVELCRM_IMAGE}" ]; then
  echo "TRAVELCRM_IMAGE is required, for example docker.io/user/travelcrm-backend:<git-sha>." >&2
  exit 1
fi

if [ ! -f "${COMPOSE_FILE}" ]; then
  echo "Missing ${APP_DIR}/${COMPOSE_FILE}." >&2
  exit 1
fi

if [ ! -f "${COMPOSE_ENV_FILE}" ]; then
  echo "Missing ${APP_DIR}/${COMPOSE_ENV_FILE}. Copy deploy/hostinger.compose.env.example and fill it on the VPS." >&2
  exit 1
fi

app_env_file="${APP_ENV_FILE:-}"
if [ -z "${app_env_file}" ]; then
  app_env_file="$(grep -E '^APP_ENV_FILE=' "${COMPOSE_ENV_FILE}" | tail -n 1 | cut -d= -f2- || true)"
fi
app_env_file="${app_env_file:-/etc/travelcrm/travelcrm.env}"

if [ ! -f "${app_env_file}" ]; then
  echo "Missing ${app_env_file}. Copy deploy/travelcrm.env.example and fill app secrets first." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required. Install the docker-compose-plugin package on the VPS." >&2
  exit 1
fi

if [ -n "${DOCKERHUB_USERNAME:-}" ] && [ -n "${DOCKERHUB_TOKEN:-}" ]; then
  printf '%s' "${DOCKERHUB_TOKEN}" | docker login -u "${DOCKERHUB_USERNAME}" --password-stdin >/dev/null
fi

export TRAVELCRM_IMAGE
compose=(docker compose --project-name "${COMPOSE_PROJECT_NAME}" --env-file "${COMPOSE_ENV_FILE}" -f "${COMPOSE_FILE}")

echo "Deploying ${TRAVELCRM_IMAGE}"
"${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans

app_container="$("${compose[@]}" ps -q app)"
if [ -z "${app_container}" ]; then
  echo "Compose did not create an app container." >&2
  "${compose[@]}" ps
  exit 1
fi

echo "Waiting for app health check..."
for _ in $(seq 1 60); do
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}' "${app_container}" 2>/dev/null || true)"
  if [ "${health}" = "healthy" ]; then
    echo "Deployment healthy."
    "${compose[@]}" ps
    if [ "${PRUNE_OLD_IMAGES}" = "true" ]; then
      docker image prune -f --filter "until=168h" >/dev/null || true
    fi
    exit 0
  fi
  if [ "${health}" = "unhealthy" ]; then
    echo "App container became unhealthy." >&2
    "${compose[@]}" logs --tail=120 app >&2
    exit 1
  fi
  sleep 5
done

echo "Timed out waiting for a healthy app container." >&2
"${compose[@]}" ps >&2
"${compose[@]}" logs --tail=120 app >&2
exit 1
