#!/bin/bash
# Run the full e2e suite against a purpose-configured backend.
# Usage: scripts/run-e2e.sh [--headed]
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if curl -sf http://localhost:8080/api/v1/version >/dev/null 2>&1; then
  echo "[run-e2e] ERROR: something is already listening on :8080."
  echo "  Stop your dev backend first - this script starts its own with"
  echo "  deterministic test flags (yaniv-threshold=200, auto-play off)."
  exit 1
fi

echo "[run-e2e] starting backend with deterministic test flags..."
# Prefer the Maven wrapper; fall back to system mvn if the wrapper is unusable
MVN_CMD="./mvnw"
if [ ! -f "$ROOT/.mvn/wrapper/maven-wrapper.properties" ] && ! command -v mvn >/dev/null 2>&1; then
  echo "[run-e2e] ERROR: neither a working ./mvnw nor system 'mvn' found."
  exit 1
fi
command -v mvn >/dev/null 2>&1 && ! grep -q "distributionUrl" "$ROOT/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null && MVN_CMD="mvn"
(cd "$ROOT" && $MVN_CMD spring-boot:run -q -Dspring-boot.run.arguments="--game.yaniv-threshold=200 --game.auto-play-enabled=false --game.turn-timer-seconds=60 --game.yaniv-contest-timer-seconds=15") &
BACK_PID=$!
trap 'kill $BACK_PID 2>/dev/null' EXIT

echo "[run-e2e] waiting for backend..."
for i in $(seq 1 90); do
  curl -sf http://localhost:8080/api/v1/version >/dev/null && break
  sleep 1
done
curl -sf http://localhost:8080/api/v1/version >/dev/null || { echo "backend did not start"; exit 1; }

MODE="${1:-}"
cd "$ROOT/frontend"
if [ "$MODE" == "--headed" ]; then
  npm run test:e2e:headed
else
  npm run test:e2e
fi
