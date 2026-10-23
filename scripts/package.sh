#!/usr/bin/env bash
# =============================================================
# Plumb release packager
# -------------------------------------------------------------
# Build a redistributable distribution directory under
#   dist/plumb-<version>/
# containing:
#   bin/sast-scan(.bat)         launchers
#   lib/plumb-*.jar             the shaded jar
#   conf/logback.xml            default logging config
#   README.txt                  quick-start
#
# Usage:
#   ./scripts/package.sh [--output DIR] [--skip-build]
# =============================================================
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")"/.. && pwd)"
cd "$PROJECT_ROOT"

OUTPUT_DIR="$PROJECT_ROOT/dist"
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --output=*) OUTPUT_DIR="${arg#--output=}" ;;
    --output)   shift; OUTPUT_DIR="$1" ;;
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help)
      sed -n '2,15p' "$0"
      exit 0
      ;;
  esac
done

VERSION="$(grep -m1 '<version>' pom.xml | sed -E 's@.*<version>([^<]+).*@\1@')"
DIST="$OUTPUT_DIR/plumb-$VERSION"
JAR_SRC="plumb-main/target/plumb-main-$VERSION-all.jar"

if [ "$SKIP_BUILD" -eq 0 ]; then
  ./scripts/build.sh --skip-tests
fi

if [ ! -f "$JAR_SRC" ]; then
  echo "ERROR: missing $JAR_SRC. Build first." >&2
  exit 1
fi

rm -rf "$DIST"
mkdir -p "$DIST/bin" "$DIST/lib" "$DIST/conf"

cp "$JAR_SRC" "$DIST/lib/"
cp plumb-main/src/main/resources/logback.xml "$DIST/conf/"
cp plumb-main/src/main/resources/default-config.yaml "$DIST/conf/"
# Note: we deliberately don't ship the examples/ tree
# (it duplicates megabytes of .java files for the doc
# fixtures). The user passes their own -i <path> at run
# time; the conf/default-config.yaml is enough to
# bootstrap a working scan.

# POSIX launcher
cat > "$DIST/bin/sast-scan" <<EOF
#!/usr/bin/env bash
# Plumb sast-scan launcher
DIR="\$(cd "\$(dirname "\$0")"/.. && pwd)"
exec java -cp "\$DIR/lib/*" -Dlogback.configurationFile="\$DIR/conf/logback.xml" org.plumb.main.SastMain "\$@"
EOF
chmod +x "$DIST/bin/sast-scan"

# Windows launcher
cat > "$DIST/bin/sast-scan.bat" <<'EOF'
@echo off
rem Plumb sast-scan launcher (Windows)
setlocal
set DIR=%~dp0..
java -cp "%DIR%\lib\*" -Dlogback.configurationFile="%DIR%\conf\logback.xml" org.plumb.main.SastMain %*
EOF

# Quick start
cat > "$DIST/README.txt" <<EOF
Plumb $VERSION
==============

Run:
  bin/sast-scan --help

Try the default config on your own source tree:
  bin/sast-scan -c conf/default-config.yaml -i path/to/your/src

Edit conf/default-config.yaml to:
  - turn on AI dedup (set ai.enabled: true + OPENAI_API_KEY env var)
  - switch call-graph strategy (default is "global" — cross-TU)
  - enable / disable checker families

Outputs NDJSON to stdout by default; redirect to a file with -o.

Layout:
  bin/sast-scan(.bat)         POSIX / Windows launchers
  lib/plumb-*.jar             the shaded jar (all deps inlined)
  conf/default-config.yaml    baseline config to edit
  conf/logback.xml             log config
EOF

echo "Distribution written to: $DIST"
ls -la "$DIST"
