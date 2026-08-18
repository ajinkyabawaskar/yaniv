#!/bin/bash
# Build script that injects version from application.properties into frontend

set -e

# Get the version from backend application.properties
VERSION_FILE="../src/main/resources/application.properties"
if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Extract app.version value
APP_VERSION=$(grep '^app.version=' "$VERSION_FILE" | cut -d'=' -f2 | tr -d ' ')
if [ -z "$APP_VERSION" ]; then
    echo "Error: app.version not found in $VERSION_FILE"
    exit 1
fi

echo "Building frontend with version: $APP_VERSION"

# Export as REACT_APP_VERSION (embedded at build time by react-scripts)
export REACT_APP_VERSION="$APP_VERSION"

# Run the build directly using local react-scripts binary
./node_modules/.bin/react-scripts build

echo "Frontend built with version $APP_VERSION embedded"