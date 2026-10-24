#!/usr/bin/env sh
#
# Format the Java sources with the pinned google-java-format.
#
# Thin wrapper around scripts/format.py, which holds the single
# implementation of the formatting rule (the file set, the exclusion
# list, the pinned formatter version). Keeping the logic in one place
# means Windows, Linux and macOS enforce exactly the same thing.
#
# Usage:
#   scripts/format.sh            # rewrite the sources in place
#   scripts/format.sh --check    # report unformatted files, change nothing

set -e

cd "$(dirname "$0")/.."

if ! command -v python >/dev/null 2>&1; then
    echo "python not found on PATH" >&2
    exit 2
fi

exec python scripts/format.py "$@"
