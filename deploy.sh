#!/usr/bin/env bash
# Yanif Complete Deployment Script
# Run from project root: ./deploy.sh [patch|minor|major]
# Defaults to patch version bump

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Config
SERVER_USER="ajinkya"
SERVER_HOST="20.198.4.81"
SERVER_JAR_PATH="/opt/yaniv/yaniv.jar"
SERVICE_NAME="yaniv"

# Parse version bump type
VERSION_BUMP="${1:-patch}"

if [[ ! "$VERSION_BUMP" =~ ^(patch|minor|major)$ ]]; then
  echo -e "${RED}Error: Invalid version bump type. Use: patch, minor, or major${NC}"
  exit 1
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Yanif Deployment Script${NC}"
echo -e "${BLUE}  Version bump: $VERSION_BUMP${NC}"
echo -e "${BLUE}========================================${NC}"

# Step 1: Bump version
echo -e "\n${YELLOW}[1/8] Bumping version ($VERSION_BUMP)...${NC}"
./bump-version.sh "$VERSION_BUMP"
NEW_VERSION=$(grep '^app.version=' src/main/resources/application.properties | cut -d'=' -f2 | tr -d ' ')
echo -e "${GREEN}Version: $NEW_VERSION${NC}"

# Step 2: Build frontend
echo -e "\n${YELLOW}[2/8] Building frontend...${NC}"
cd frontend
npm run build
cd ..

# Step 3: Build backend JAR
echo -e "\n${YELLOW}[3/8] Building backend JAR...${NC}"
mvn clean package -DskipTests=true

# Find the built JAR
JAR_FILE=$(ls target/yanif-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR_FILE" ]]; then
  echo -e "${RED}Error: No JAR file found in target/${NC}"
  exit 1
fi
echo -e "${GREEN}Found JAR: $JAR_FILE${NC}"

# Step 4: Sync version to server config
# The service runs with spring.config.location=file:/opt/yaniv/application-prod.properties,
# which REPLACES the JAR-baked application.properties at runtime. So the server file is
# authoritative for app.version (and everything else in it) — keep it in sync here, and
# never hand-edit its app.version line. The JAR/frontend values still matter: the frontend
# embeds REACT_APP_VERSION at build time, and the JAR value is the fallback everywhere else.
echo -e "\n${YELLOW}[4/8] Syncing app.version ($NEW_VERSION) to server config...${NC}"
ssh "$SERVER_USER@$SERVER_HOST" "sudo sed -i 's/^app.version=.*/app.version=$NEW_VERSION/' /opt/yaniv/application-prod.properties; grep -q '^app.version=' /opt/yaniv/application-prod.properties || echo 'app.version=$NEW_VERSION' | sudo tee -a /opt/yaniv/application-prod.properties >/dev/null; grep '^app.version=' /opt/yaniv/application-prod.properties"

# Step 5: Copy JAR to server
echo -e "\n${YELLOW}[5/8] Copying JAR to server ($SERVER_USER@$SERVER_HOST)...${NC}"
scp "$JAR_FILE" "$SERVER_USER@$SERVER_HOST:$SERVER_JAR_PATH"

# Step 6: Restart service
echo -e "\n${YELLOW}[6/8] Restarting service on server...${NC}"
ssh "$SERVER_USER@$SERVER_HOST" "sudo systemctl restart $SERVICE_NAME"

# Step 7: Check status
echo -e "\n${YELLOW}[7/8] Checking service status...${NC}"
ssh "$SERVER_USER@$SERVER_HOST" "sudo systemctl status $SERVICE_NAME --no-pager"

# Step 8: Verify the running server reports the deployed version
# (cache-buster query so Cloudflare never serves a stale /version response)
echo -e "\n${YELLOW}[8/8] Verifying deployed version ($NEW_VERSION)...${NC}"
DEPLOYED_VERSION=""
for _ in $(seq 1 30); do
  DEPLOYED_VERSION=$(curl -s --max-time 10 "https://yaniv.ajinkyabawaskar.com/api/v1/version?t=$(date +%s)" | grep -o '"version":"[^"]*"' | cut -d'"' -f4 || true)
  if [[ "$DEPLOYED_VERSION" == "$NEW_VERSION" ]]; then
    break
  fi
  sleep 10
done
if [[ "$DEPLOYED_VERSION" != "$NEW_VERSION" ]]; then
  echo -e "${RED}Version mismatch: expected $NEW_VERSION, got '${DEPLOYED_VERSION:-<no response>}'${NC}"
  exit 1
fi
echo -e "${GREEN}Version verified: $DEPLOYED_VERSION${NC}"

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  Deployment complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "\nTo view logs, run:"
echo -e "  ssh $SERVER_USER@$SERVER_HOST \"tail -f /var/log/yaniv/app.log\""