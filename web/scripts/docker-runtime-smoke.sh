#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
run_id="$(date +%s%N)-$$-${RANDOM}"
network="iwrite-runtime-smoke-${run_id}"
database="iwrite-runtime-smoke-db-${run_id}"
combined="iwrite-runtime-smoke-combined-${run_id}"
frontend="iwrite-runtime-smoke-frontend-${run_id}"
combined_image="iwrite-runtime-smoke-combined:${run_id}"
frontend_image="iwrite-runtime-smoke-frontend:${run_id}"
network_created=false
database_created=false
combined_created=false
frontend_created=false
combined_image_created=false
frontend_image_created=false

cleanup() {
  if [ "$frontend_created" = true ]; then docker rm -f "$frontend" >/dev/null 2>&1 || true; fi
  if [ "$combined_created" = true ]; then docker rm -f "$combined" >/dev/null 2>&1 || true; fi
  if [ "$database_created" = true ]; then docker rm -f "$database" >/dev/null 2>&1 || true; fi
  if [ "$network_created" = true ]; then docker network rm "$network" >/dev/null 2>&1 || true; fi
  if [ "$frontend_image_created" = true ]; then docker image rm -f "$frontend_image" >/dev/null 2>&1 || true; fi
  if [ "$combined_image_created" = true ]; then docker image rm -f "$combined_image" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail_with_logs() {
  echo "Runtime smoke failed: $*" >&2
  docker logs "$frontend" >&2 2>/dev/null || true
  docker logs "$combined" >&2 2>/dev/null || true
  exit 1
}

assert_no_runtime_install() {
  local container="$1"
  if docker logs "$container" 2>&1 | rg -i 'Installing TypeScript|Installing devDependencies|yarn add|npm (install|add)'; then
    fail_with_logs "$container attempted a package installation during startup"
  fi
  if docker exec "$container" node -e "require.resolve('typescript', { paths: ['/app/frontend'] })" >/dev/null 2>&1; then
    fail_with_logs "$container contains TypeScript in its production dependencies"
  fi
}

wait_for_ping() {
  local container="$1"
  local port="$2"
  local attempts=90
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if docker logs "$container" 2>&1 | rg -i 'Installing TypeScript|Installing devDependencies|yarn add|npm (install|add)'; then
      fail_with_logs "$container attempted a package installation during startup"
    fi
    if docker exec "$container" node -e \
      "fetch('http://127.0.0.1:${port}/api/ping').then(r => process.exit(r.status === 200 ? 0 : 1)).catch(() => process.exit(1))" \
      >/dev/null 2>&1; then
      return 0
    fi
    if [ "$(docker inspect "$container" --format '{{.State.Running}}')" != true ]; then
      fail_with_logs "$container exited before /api/ping became ready"
    fi
    sleep 2
  done
  fail_with_logs "$container did not serve /api/ping within $((attempts * 2)) seconds"
}

docker build -t "$combined_image" -f "$repo_root/Dockerfile" "$repo_root"
combined_image_created=true
docker build -t "$frontend_image" -f "$repo_root/web/Dockerfile" \
  --build-arg "BACKEND_ORIGIN=http://${combined}:8085" "$repo_root/web"
frontend_image_created=true

# This network has no external route. PostgreSQL uses a fresh tmpfs mount; no pre-existing volume,
# developer Compose project, or host database is attached.
docker network create --internal "$network" >/dev/null
network_created=true
docker run -d --name "$database" --network "$network" --network-alias runtime-db \
  --tmpfs /var/lib/postgresql/data:rw,size=512m \
  -e POSTGRES_DB=iwrite -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  postgres:16@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d >/dev/null
database_created=true

for ((attempt = 1; attempt <= 60; attempt++)); do
  if docker exec "$database" pg_isready -U postgres -d iwrite >/dev/null 2>&1; then
    break
  fi
  if [ "$attempt" = 60 ]; then
    fail_with_logs "isolated PostgreSQL did not become ready"
  fi
  sleep 1
done

docker run -d --name "$combined" --network "$network" \
  -e DB_URL=jdbc:postgresql://runtime-db:5432/iwrite \
  -e DB_USERNAME=postgres -e DB_PASSWORD=postgres \
  "$combined_image" >/dev/null
combined_created=true

wait_for_ping "$combined" 8080
assert_no_runtime_install "$combined"

# The standalone frontend proxies to the backend inside the isolated combined-image container.
docker run -d --name "$frontend" --network "$network" \
  -e "BACKEND_ORIGIN=http://${combined}:8085" \
  --network-alias runtime-frontend "$frontend_image" >/dev/null
frontend_created=true

wait_for_ping "$frontend" 3000
assert_no_runtime_install "$frontend"

echo "Both production images started without external network or runtime package installation."
echo "Both /api/ping endpoints reached the isolated Spring Boot backend."
