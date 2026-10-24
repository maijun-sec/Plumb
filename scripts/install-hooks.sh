#!/usr/bin/env sh
#
# Install the repository's git hooks into .git/hooks.
#
# The hook is tracked in scripts/hooks/ so it is version-controlled and
# travels with the repository; this script copies it into the place git
# actually looks. A fresh clone needs to run this once (or the hook can
# be installed automatically by a bootstrap step).
#
# Usage:
#     sh scripts/install-hooks.sh

set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

HOOKS_DIR="$(git rev-parse --git-dir)/hooks"
SRC_DIR="$REPO_ROOT/scripts/hooks"

if [ ! -d "$HOOKS_DIR" ]; then
    echo "no hooks directory at $HOOKS_DIR" >&2
    exit 1
fi

if [ ! -d "$SRC_DIR" ]; then
    echo "no tracked hooks at $SRC_DIR" >&2
    exit 1
fi

installed=0
for hook in "$SRC_DIR"/*; do
    name="$(basename "$hook")"
    cp "$hook" "$HOOKS_DIR/$name"
    chmod +x "$HOOKS_DIR/$name"
    echo "installed $name"
    installed=$((installed + 1))
done

if [ "$installed" -eq 0 ]; then
    echo "no hooks to install"
    exit 0
fi

echo
echo "Hooks installed. The pre-commit hook refuses a commit that contains"
echo "unformatted Java. Run 'scripts/format.ps1' (or format.sh) to fix."
