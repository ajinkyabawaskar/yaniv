#!/bin/sh
# Point git at the repo's checked-in hooks. Run once per clone:
#
#   ./scripts/install-hooks.sh
#
# Git config is per-clone and not version controlled, so every developer needs
# to run this once. Undo with: git config --unset core.hooksPath

set -e

cd "$(dirname "$0")/.."
git config core.hooksPath scripts/hooks

echo "Hooks installed: $(ls scripts/hooks | tr '\n' ' ')"
echo "Bypass any hook for one commit with: git commit --no-verify"
