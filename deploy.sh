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
echo -e "\n${YELLOW}[1/6] Bumping version ($VERSION_BUMP)...${NC}"
./bump-version.sh "$VERSION_BUMP"

# Step 2: Build frontend
echo -e "\n${YELLOW}[2/6] Building frontend...${NC}"
cd frontend
npm run build
cd ..

# Step 3: Build backend JAR
echo -e "\n${YELLOW}[3/6] Building backend JAR...${NC}"
mvn clean package -DskipTests=true

# Find the built JAR
JAR_FILE=$(ls target/yanif-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR_FILE" ]]; then
  echo -e "${RED}Error: No JAR file found in target/${NC}"
  exit 1
fi
echo -e "${GREEN}Found JAR: $JAR_FILE${NC}"

# Step 4: Copy JAR to server
echo -e "\n${YELLOW}[4/6] Copying JAR to server ($SERVER_USER@$SERVER_HOST)...${NC}"
scp "$JAR_FILE" "$SERVER_USER@$SERVER_HOST:$SERVER_JAR_PATH"

# Step 5: Restart service
echo -e "\n${YELLOW}[5/6] Restarting service on server...${NC}"
ssh "$SERVER_USER@$SERVER_HOST" "sudo systemctl restart $SERVICE_NAME"

# Step 6: Check status
echo -e "\n${YELLOW}[6/6] Checking service status...${NC}"
ssh "$SERVER_USER@$SERVER_HOST" "sudo systemctl status $SERVICE_NAME --no-pager"

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  Deployment complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "\nTo view logs, run:"
echo -e "  ssh $SERVER_USER@$SERVER_HOST \"tail -f /var/log/yaniv/app.log\""