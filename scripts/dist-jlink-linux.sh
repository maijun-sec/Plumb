#!/usr/bin/env bash
# =============================================================
# Plumb "解压即用" 打包 (Linux / macOS) — jlink + tar.gz
# -------------------------------------------------------------
# 把 shaded uber-jar 加上 jlink 输出的最小 JRE,打进一个
# 自包含的 tar.gz。用户机器无需任何 Java 环境,解压即跑。
#
# Usage:
#   ./scripts/dist-jlink-linux.sh [--skip-build] [--output DIR] [--arch x64|arm64]
#
# Output:
#   dist-jlink/plumb-<version>-jre-linux-x64.tar.gz (~180 MB)
# =============================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")"/.. && pwd)"
cd "$PROJECT_ROOT"

OUTPUT_DIR="$PROJECT_ROOT/dist-jlink"
SKIP_BUILD=0
SKIP_JLINK=0
ARCH="x64"

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --no-jlink)   SKIP_JLINK=1 ;;
    --output=*)    OUTPUT_DIR="${arg#--output=}" ;;
    --output)      shift; OUTPUT_DIR="$1" ;;
    --arch=*)      ARCH="${arg#--arch=}" ;;
    --arch)        shift; ARCH="$1" ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

VERSION="$(grep -m1 '<version>' pom.xml | sed -E 's@.*<version>([^<]+).*@\1@')"
JAR_NAME="plumb-main-${VERSION}-all.jar"
JAR_PATH="plumb-main/target/${JAR_NAME}"
DIST="$OUTPUT_DIR/plumb-${VERSION}-jre-linux-${ARCH}"

# ---------- 1. Environment preparation -----------------------
echo "=== [1/5] Environment preparation ==="

# 1a. JDK 17+ (jlink 是 JDK 自带,JRE 没有)
if ! command -v javac >/dev/null 2>&1; then
  echo "ERROR: 'javac' not found. jlink requires JDK 17+, not JRE." >&2
  echo "       Install JDK 17:    apt install openjdk-17-jdk" >&2
  echo "                         brew install openjdk@17" >&2
  echo "       Then export JAVA_HOME=/path/to/jdk" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "WARNING: JAVA_HOME not set. Trying to infer..." >&2
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  echo "         Inferred JAVA_HOME=$JAVA_HOME" >&2
fi
if [ ! -d "$JAVA_HOME/jmods" ]; then
  if [ "$SKIP_JLINK" -eq 1 ]; then
    echo "WARNING: \$JAVA_HOME/jmods not found at $JAVA_HOME/jmods. --no-jlink set, skipping bundled JRE." >&2
  else
    echo "ERROR: \$JAVA_HOME/jmods not found at $JAVA_HOME/jmods" >&2
    echo "" >&2
    echo "jlink needs the JDK's jmods/ directory to build a custom JRE." >&2
    echo "Many JDK distributions (Temurin 17+ 'JRE' installers, some Linux" >&2
    echo "packages) don't ship jmods. Fixes:" >&2
    echo "" >&2
    echo "  1) Install a JDK that ships jmods:" >&2
    echo "     - https://jdk.java.net/  (OpenJDK reference builds, includes jmods)" >&2
    echo "     - brew install openjdk@17   (macOS Homebrew)" >&2
    echo "     - apt install openjdk-17-jdk   (Debian/Ubuntu)" >&2
    echo "" >&2
    echo "  2) Or skip the bundled JRE with --no-jlink:" >&2
    echo "     ./scripts/dist-jlink-linux.sh --no-jlink" >&2
    echo "     (the dist will need a system-installed JRE 17+ to run)" >&2
    exit 1
  fi
fi
echo "  JAVA_HOME: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/^/  /'

# 1b. tar
if ! command -v tar >/dev/null 2>&1; then
  echo "ERROR: 'tar' not found." >&2; exit 1
fi

# 1c. disk (180 MB output)
FREE_KB="$(df -k . 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 524288 ]; then
  echo "WARNING: < 500 MB free disk. jlink output + tar.gz may not fit." >&2
fi

# ---------- 2. Maven build ---------------------------------
LOG="${PROJECT_ROOT}/build.log"
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "=== [2/5] Maven build (log: $LOG) ==="
  if [ ! -f "$JAR_PATH" ]; then
    mvn -B -fae -pl plumb-main -am package -DskipTests 2>&1 | tee "$LOG" >&2
  else
    echo "  (cached) $JAR_PATH exists, skipping rebuild"
  fi
else
  echo "=== [2/5] Maven build (SKIPPED via --skip-build) ==="
fi

if [ ! -f "$JAR_PATH" ]; then
  echo "ERROR: missing $JAR_PATH. Build first (drop --skip-build)." >&2
  exit 1
fi
JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
echo "  shaded jar: $JAR_PATH ($JAR_SIZE)"

# ---------- 3. jlink minimal JRE ----------------------------
echo "=== [3/5] jlink minimal JRE ==="

# Plumb uses these JDK modules. Verified by
#   mvn -pl plumb-main dependency:tree | grep jmod
# and javaparser-symbol-solver-core's reflection usage.
JMODS=(
  java.base
  java.logging
  java.net.http
  java.compiler
  java.instrument
  java.management
  java.naming
  java.security.jgss
  java.sql
  java.xml
  jdk.crypto.ec
  jdk.httpserver
  jdk.unsupported       # SootUp
  jdk.zipfs             # z3 turnkey
)

JRE="$DIST/jre"
if [ "$SKIP_JLINK" -eq 1 ] || [ ! -d "$JAVA_HOME/jmods" ]; then
  if [ "$SKIP_JLINK" -eq 1 ]; then
    echo "  --no-jlink: skipping jlink. Dist will require system JRE 17+ to run."
  else
    echo "  WARNING: jmods/ not found; skipping jlink. Dist will require system JRE 17+ to run."
  fi
elif [ -d "$JRE" ]; then
  echo "  (cached) $JRE exists, skipping jlink"
else
  echo "  jlink --module-path $JAVA_HOME/jmods --add-modules $(IFS=,; echo "${JMODS[*]}") ..."
  "$JAVA_HOME/bin/jlink" \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules "$(IFS=,; echo "${JMODS[*]}")" \
    --strip-debug \
    --compress=zip-9 \
    --no-header-files \
    --no-man-pages \
    --output "$JRE"
fi
if [ -d "$JRE" ]; then
  JRE_SIZE=$(du -sh "$JRE" | cut -f1)
  echo "  jre:        $JRE ($JRE_SIZE)"
fi

# ---------- 4. Dist layout ----------------------------------
echo "=== [4/5] Assemble dist ==="

rm -rf "$DIST"
mkdir -p "$DIST/bin" "$DIST/lib" "$DIST/conf" "$DIST/docs"

# 4a. shaded jar
cp "$JAR_PATH" "$DIST/lib/"

# 4b. configs
cp plumb-main/src/main/resources/default-config.yaml "$DIST/conf/"
cp plumb-main/src/main/resources/logback.xml "$DIST/conf/"

# 4c. jlink JRE (if not skipped)
if [ "$SKIP_JLINK" -eq 0 ] && [ -d "$JAVA_HOME/jmods" ] && [ ! -d "$DIST/jre" ]; then
  echo "  jlink -> $DIST/jre"
  "$JAVA_HOME/bin/jlink" \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules "$(IFS=,; echo "${JMODS[*]}")" \
    --strip-debug \
    --compress=zip-9 \
    --no-header-files \
    --no-man-pages \
    --output "$DIST/jre" 2>&1 | sed 's/^/  /'
fi
if [ -d "$DIST/jre" ]; then
  JRE_SIZE=$(du -sh "$DIST/jre" | cut -f1)
  echo "  jre:        $DIST/jre ($JRE_SIZE)"
else
  echo "  WARNING: no jre/ in dist. User must have JRE 17+ on PATH or set JAVA_HOME."
fi

# 4d. launcher scripts
cat > "$DIST/bin/plumb" <<'EOF'
#!/usr/bin/env bash
# Plumb launcher (Linux / macOS)
# Uses bundled jre/ if present, else falls back to $JAVA_HOME or PATH.
DIR="$(cd "$(dirname "$0")"/.. && pwd)"
JRE="$DIR/jre"
if   [ -x "$JRE/bin/java" ];     then JAVA="$JRE/bin/java"
elif [ -x "${JAVA_HOME:-}/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"
else JAVA="$(command -v java || true)"
fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "ERROR: no java found. Set JAVA_HOME or install JDK 17+." >&2
  exit 1
fi
JVM_OPTS="${PLUMB_JAVA_OPTS:--Xmx4g -XX:+UseG1GC}"
if [ "${PLUMB_DEBUG:-false}" = "true" ]; then
  JVM_OPTS="$JVM_OPTS -Dlogback.configurationFile=$DIR/conf/logback.xml -Droot.level=DEBUG"
fi
exec "$JAVA" $JVM_OPTS \
  -Dplumb.home="$DIR" \
  -Dlogback.configurationFile="$DIR/conf/logback.xml" \
  -cp "$DIR/lib/*" \
  org.plumb.main.SastMain "$@"
EOF
chmod +x "$DIST/bin/plumb"

cat > "$DIST/bin/plumb.bat" <<'EOF'
@echo off
rem Plumb launcher (Windows) — used inside the jre-bundled
rem Linux-x64 tar.gz for completeness; Windows users should
rem run dist-jlink-windows.ps1 instead.
setlocal
set DIR=%~dp0..
set JRE=%DIR%\jre
if exist "%JRE%\bin\java.exe" (
  set JAVA=%JRE%\bin\java.exe
) else if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set JAVA=%JAVA_HOME%\bin\java.exe
) else (
  where java >nul 2>nul && (set JAVA=java) || (
    echo ERROR: no java found. Set JAVA_HOME or install JDK 17+.
    exit /b 1
  )
)
if not defined PLUMB_JAVA_OPTS set PLUMB_JAVA_OPTS=-Xmx4g -XX:+UseG1GC
"%JAVA%" %PLUMB_JAVA_OPTS% -Dplumb.home="%DIR%" -Dlogback.configurationFile="%DIR%\conf\logback.xml" -cp "%DIR%\lib\*" org.plumb.main.SastMain %*
EOF

# 4e. docs (R684 — comprehensive review)
cp Plumb-OVERVIEW.md        "$DIST/docs/"
cp docs/CHECKERS.md         "$DIST/docs/"
cp docs/ENVIRONMENT.md      "$DIST/docs/"
cp doc/usage/CLI.md         "$DIST/docs/" 2>/dev/null || true

# 4f. dist README (template + sed for cross-platform consistency)
DATE="$(date -u +'%Y-%m-%d')"
TEMPLATES_DIR="$(cd "$(dirname "$0")" && pwd)/templates"
sed -e "s/__VERSION__/${VERSION}/g" \
    -e "s/__ARCH__/${ARCH}/g" \
    "$TEMPLATES_DIR/README-linux.md.template" > "$DIST/README.md"

# 4g. CHANGELOG
sed -e "s/__VERSION__/${VERSION}/g" \
    -e "s/__ARCH__/${ARCH}/g" \
    -e "s/__DATE__/${DATE}/g" \
    "$TEMPLATES_DIR/CHANGELOG.template" > "$DIST/CHANGELOG.md"

# ---------- 5. tar.gz ---------------------------------------
echo "=== [5/5] Pack tar.gz ==="
TARBALL="$OUTPUT_DIR/plumb-${VERSION}-jre-linux-${ARCH}.tar.gz"
tar -czf "$TARBALL" -C "$OUTPUT_DIR" "plumb-${VERSION}-jre-linux-${ARCH}"
TARBALL_SIZE=$(du -h "$TARBALL" | cut -f1)
echo ""
echo "============================================================"
echo "  Output:    $TARBALL ($TARBALL_SIZE)"
echo "  Layout:    $DIST/"
echo "  Verify:    tar -tzf $TARBALL | head"
echo "  Test:      $DIST/bin/plumb --version"
echo "============================================================"
