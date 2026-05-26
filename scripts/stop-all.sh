#!/bin/bash
# Stop all Mnemoscape containers
echo "=== Stopping Mnemoscape Platform ==="
cd "$(dirname "$0")/.."

docker compose -f docker/docker-compose.all.yml down --remove-orphans 2>/dev/null || \
docker compose -f docker/docker-compose.services.yml down --remove-orphans 2>/dev/null || true
docker compose -f docker/docker-compose.yml down --remove-orphans 2>/dev/null || true

echo "=== All containers stopped ==="
