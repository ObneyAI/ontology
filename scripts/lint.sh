#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if ! command -v bb >/dev/null 2>&1; then
    echo "Repository lint requires Babashka (bb) on PATH." >&2
    exit 1
fi
exec bb scripts/lint.bb "$@"
