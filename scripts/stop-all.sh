#!/bin/bash
# Stop all Mnemoscape containers
echo "=== Stopping Mnemoscape Platform ==="
cd "$(dirname "$0")/.."

docker compose -f deploy/docker-compose.all.yml down --remove-orphans 2>/dev/null || \
docker compose -f deploy/docker-compose.services.yml down --remove-orphans 2>/dev/null || true
docker compose -f deploy/docker-compose.yml down --remove-orphans 2>/dev/null || true

echo "=== All containers stopped ==="
