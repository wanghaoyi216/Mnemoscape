#!/bin/bash
# Start all infrastructure middleware containers
echo "=== Starting Mnemoscape Infrastructure ==="
cd "$(dirname "$0")/.."

docker compose -f deploy/docker-compose.yml up -d

echo ""
echo "Waiting for services to be healthy..."
echo "  MySQL (3306)..."
until docker exec mnemoscape-mysql mysqladmin ping -h localhost -u root -proot123 --silent 2>/dev/null; do
  echo -n "."
  sleep 3
done
echo " ready!"

echo "  Redis (6379)..."
until [ "$(docker exec mnemoscape-redis redis-cli ping 2>/dev/null)" = "PONG" ]; do
  echo -n "."
  sleep 2
done
echo " ready!"

echo "  Nacos (8848)..."
until curl -s http://localhost:8848/nacos/v1/console/health/readiness > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done
echo " ready!"

echo "  MinIO (9000)..."
until curl -s http://localhost:9000/minio/health/live > /dev/null 2>&1; do
  echo -n "."
  sleep 3
done
echo " ready!"

echo "  Neo4j (7687)..."
until docker exec mnemoscape-neo4j cypher-shell -u neo4j -p password123 "RETURN 1" > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done
echo " ready!"

echo ""
echo "=== All infrastructure services are healthy! ==="
docker compose -f deploy/docker-compose.yml ps
