#!/bin/sh
# Fails if this commit changes game-engine behaviour without updating its spec.
# Run directly to check the current staged changes, or via the pre-commit hook.

set -e

DOC="docs/game-engine.md"

# Files that own game rules or engine lifecycle. Keep this list in step with the
# "Where the behaviour lives" table in docs/game-engine.md.
ENGINE_PATHS="
src/main/java/shop/abwork/yanif/game/
src/main/java/shop/abwork/yanif/websocket/GameStateController.java
"

PROPERTIES="src/main/resources/application.properties"

staged=$(git diff --cached --name-only --diff-filter=ACMR)
[ -n "$staged" ] || exit 0

touched=""

# A changed game.* key alters documented behaviour just as much as engine code does.
# Other properties in the same file (datasource, redis, logging) do not.
if git diff --cached -U0 -- "$PROPERTIES" | grep -Eq '^[+-][[:space:]]*game\.'; then
  touched="$touched$PROPERTIES
"
fi

for path in $ENGINE_PATHS; do
  match=$(printf '%s\n' "$staged" | grep -F "$path" || true)
  [ -n "$match" ] && touched="$touched$match
"
done

[ -n "$touched" ] || exit 0

if printf '%s\n' "$staged" | grep -qx "$DOC"; then
  exit 0
fi

cat >&2 <<MSG

  Engine changed, but $DOC did not.

  Staged engine files:
$(printf '%s' "$touched" | sed 's/^/    /')

  $DOC is the single source of truth for the rules, scoring and
  lifecycle. Update the sections your change affects and stage it:

    git add $DOC

  If this change genuinely has no effect on documented behaviour (a rename,
  a comment, a pure refactor), bypass this check:

    git commit --no-verify

MSG
exit 1
