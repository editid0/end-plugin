#!/usr/bin/env bash
# Runs Endfold's real-server integration tests on a throw-away Purpur 26.3 server.
#
#   ./testkit/run-integration.sh [path/to/purpur-or-paper.jar]
#
# Phase 1 sets up worlds, players, portals, dragon fights, loot and elytra; the server then stops. Phase 2 starts the
# same server again and verifies everything persisted. Results: <workdir>/plugins/EndfoldTestkit/results-phase{1,2}.txt
# The script exits non-zero if any test FAILs. Never point this at a production server directory.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ENDFOLD_IT_DIR:-$ROOT/target/it-server}"
JAR="${1:-}"
MC_VERSION="26.3"
PURPUR_BUILD="${PURPUR_BUILD:-2657}"

echo "==> Building Endfold and the testkit"
(cd "$ROOT" && ./mvnw -q clean package -DskipTests)
(cd "$ROOT" && ./mvnw -q -f testkit/pom.xml clean package)

rm -rf "$WORK"
mkdir -p "$WORK/plugins"
if [[ -z "$JAR" ]]; then
  echo "==> Downloading Purpur $MC_VERSION build $PURPUR_BUILD"
  curl -fsSL -o "$WORK/server.jar" "https://api.purpurmc.org/v2/purpur/$MC_VERSION/$PURPUR_BUILD/download"
else
  cp "$JAR" "$WORK/server.jar"
fi
cp "$ROOT"/target/Endfold-*.jar "$WORK/plugins/"
cp "$ROOT/testkit/target/EndfoldTestkit.jar" "$WORK/plugins/"
echo "eula=true" > "$WORK/eula.txt"
# Test-only overrides (everything else uses Endfold's defaults): fast shulker respawn so it can be observed.
mkdir -p "$WORK/plugins/Endfold"
cat > "$WORK/plugins/Endfold/config.yml" <<'CFG'
resources:
  shulkers:
    cooldown-hours: 0.01
    check-interval-seconds: 5
    max-per-city: 100
    max-population: 500
CFG
cat > "$WORK/server.properties" <<'PROPS'
online-mode=false
server-port=25601
level-seed=42
spawn-protection=0
max-players=20
view-distance=6
simulation-distance=6
PROPS

run_phase() {
  echo "==> Phase $1"
  (cd "$WORK" && timeout 900 java -Xmx4G -Dendfold.testkit.phase="$1" -jar server.jar --nogui > "phase$1.log" 2>&1) || true
  local res="$WORK/plugins/EndfoldTestkit/results-phase$1.txt"
  if [[ ! -f "$res" ]]; then
    echo "!! No results for phase $1 - see $WORK/phase$1.log"
    exit 2
  fi
  cat "$res"
}

run_phase 1
run_phase 2

if grep -h '^FAIL' "$WORK"/plugins/EndfoldTestkit/results-phase*.txt > /dev/null; then
  echo "==> Integration tests FAILED"
  exit 1
fi
echo "==> Integration tests passed (SKIP lines, if any, are listed above)"
