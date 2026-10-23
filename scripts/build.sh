#!/usr/bin/env bash
# =============================================================
# Plumb build script
# -------------------------------------------------------------
# Build a runnable `sast-scan` JAR (uber-jar / shade) from the
# Plumb multi-module Maven project at the repository root.
#
# Usage:
#   ./scripts/build.sh                 # full build + tests
#   ./scripts/build.sh --skip-tests    # skip unit tests
#   ./scripts/build.sh --clean         # `mvn clean` first
#   ./scripts/build.sh --no-shade      # build thin jars only
#   ./scripts/build.sh --help
#
# Output:
#   plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
#
# Log:
#   build.log (in repo root)
# =============================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")"/.. && pwd)"
cd "$PROJECT_ROOT"

VERSION="$(grep -m1 '<version>' pom.xml | sed -E 's@.*<version>([^<]+).*@\1@')"
JAR_NAME="plumb-main-${VERSION}-all.jar"
JAR_PATH="plumb-main/target/${JAR_NAME}"

# ---------- 1. Environment preparation ----------------------
echo "=== [1/4] Environment preparation ==="

# 1a. Java
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: 'java' not found on PATH." >&2
  echo "       Install JDK 17 (or newer) and add it to PATH." >&2
  echo "       macOS:   brew install openjdk@17" >&2
  echo "       Linux:   apt-get install openjdk-17-jdk" >&2
  echo "       Windows: choco install temurin17" >&2
  exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -1)"
echo "  java:    ${JAVA_VER}"
case "$JAVA_VER" in
  *\"17\"|*\"21\"|*\"22\"*) ;;
  *) echo "WARNING: Plumb targets Java 17. Detected: ${JAVA_VER}" >&2 ;;
esac

# 1b. Maven
if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: 'mvn' not found on PATH." >&2
  echo "       Install Maven 3.8+ (https://maven.apache.org/download.cgi)." >&2
  exit 1
fi
MVN_VER="$(mvn -v 2>&1 | head -1)"
echo "  mvn:     ${MVN_VER}"

# 1c. Disk and memory
FREE_KB="$(df -k . 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "$FREE_KB" ]; then
  FREE_GB=$(awk "BEGIN {printf \"%.1f\", $FREE_KB/1024/1024}")
  echo "  disk:    ${FREE_GB} GB free"
  if [ "$FREE_KB" -lt 1048576 ]; then
    echo "WARNING: < 1 GB free disk. Maven may fail to download dependencies." >&2
  fi
fi

# 1d. Network — try a HEAD request to Maven Central
if command -v curl >/dev/null 2>&1; then
  if curl -fsS -o /dev/null --max-time 5 https://repo.maven.apache.org/maven2/ 2>/dev/null; then
    echo "  network: Maven Central reachable"
  else
    echo "WARNING: cannot reach Maven Central. Some dependencies may not download." >&2
  fi
fi

# ---------- 2. Argument parsing ----------------------------
SKIP_TESTS=0
DO_CLEAN=0
DO_SHADE=1
for arg in "$@"; do
  case "$arg" in
    --skip-tests)  SKIP_TESTS=1 ;;
    --clean)       DO_CLEAN=1  ;;
    --no-shade)    DO_SHADE=0  ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

# ---------- 3. Maven build --------------------------------
LOG="${PROJECT_ROOT}/build.log"
echo "=== [2/4] Maven build (log: ${LOG}) ==="
MVN_ARGS=(-B -fae)
[ "$SKIP_TESTS" -eq 1 ] && MVN_ARGS+=(-DskipTests)
[ "$DO_CLEAN"  -eq 1 ] && MVN_ARGS+=(clean)
if [ "$DO_SHADE" -eq 1 ]; then
  MVN_ARGS+=(-pl plumb-main -am package)
else
  MVN_ARGS+=(package)
fi

# shellcheck disable=SC2086
mvn "${MVN_ARGS[@]}" 2>&1 | tee "$LOG"

# ---------- 4. Summary ------------------------------------
echo "=== [3/4] Build summary ==="
if [ -f "$JAR_PATH" ]; then
  SIZE=$(du -h "$JAR_PATH" | cut -f1)
  echo "  shaded jar:  ${JAR_PATH} (${SIZE})"
else
  if [ "$DO_SHADE" -eq 0 ]; then
    echo "  --no-shade requested; thin jar is at plumb-main/target/plumb-main-${VERSION}.jar"
  else
    echo "  WARNING: shaded jar not produced. Check ${LOG}." >&2
    exit 1
  fi
fi

if [ "$SKIP_TESTS" -eq 0 ]; then
  PASS=$(grep -c "Tests run:.*Failures: 0, Errors: 0" "$LOG" 2>/dev/null || echo 0)
  echo "  test files passed: ${PASS}"
fi

echo "=== [4/4] Done ==="
echo "Run:   java -jar ${JAR_PATH} --help"
