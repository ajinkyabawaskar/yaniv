#!/bin/bash
# Auto-increment version in application.properties
# Usage: ./bump-version.sh [patch|minor|major]

set -e

# Get project root (where this script is located)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$PROJECT_ROOT/src/main/resources/application.properties"
BUMP_TYPE="${1:-patch}"

if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

# Extract current version
CURRENT_VERSION=$(grep '^app.version=' "$VERSION_FILE" | cut -d'=' -f2 | tr -d ' ')
if [ -z "$CURRENT_VERSION" ]; then
    echo "Error: app.version not found in $VERSION_FILE"
    exit 1
fi

echo "Current version: $CURRENT_VERSION"

# Parse semver (major.minor.patch)
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

case "$BUMP_TYPE" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch|*)
        PATCH=$((PATCH + 1))
        ;;
esac

NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
echo "New version: $NEW_VERSION"

# Update application.properties
sed -i.bak "s/^app.version=.*/app.version=$NEW_VERSION/" "$VERSION_FILE"
rm -f "$VERSION_FILE.bak"

echo "Updated $VERSION_FILE"

# Also update frontend package.json version for consistency
FRONTEND_PKG="$PROJECT_ROOT/frontend/package.json"
if [ -f "$FRONTEND_PKG" ]; then
    sed -i.bak "s/\"version\": \".*\"/\"version\": \"$NEW_VERSION\"/" "$FRONTEND_PKG"
    rm -f "$FRONTEND_PKG.bak"
    echo "Updated frontend/package.json"
fi