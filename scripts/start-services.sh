#!/bin/bash
# Build and start all microservices
echo "=== Building Mnemoscape Services ==="
cd "$(dirname "$0")/.."

echo "Building all services with Maven..."
cd backend
./mvnw clean package -DskipTests
if [ $? -ne 0 ]; then
  echo "Maven build failed!"
  exit 1
fi
cd ..

echo ""
echo "=== Starting Mnemoscape Platform ==="
docker compose -f deploy/docker-compose.all.yml up -d --build

echo ""
echo "Waiting for services to start..."
sleep 30

echo ""
echo "=== Service Health Check ==="
for port in 5173 8080 8081 8082 8083 8084 8085; do
  if curl -s http://localhost:$port/actuator/health > /dev/null 2>&1; then
    echo "  Port $port: healthy"
  else
    echo "  Port $port: NOT RESPONDING"
  fi
done

echo ""
echo "=== All services started! ==="
