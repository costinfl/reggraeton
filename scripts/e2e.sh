#!/usr/bin/env bash
# End-to-end check of reggraeton: builds it, converts samples/legacy-shop in both modes and runs
# the generated Playwright tests against the demo shop (samples/demo-app).
#
# Expected outcome per mode: 10 tests, 8 pass, 2 fail by design:
#   - "Browser console stays clean on the search page": TODO(reggraeton) stub (Selenium browser logs)
#   - "Search suggestions": undefined step
#
# Usage: scripts/e2e.sh                         # installed Microsoft Edge, headless
#        BROWSER_CHANNEL=chromium scripts/e2e.sh
#        HEADLESS=false scripts/e2e.sh          # windowed
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8080}"
export BROWSER_CHANNEL="${BROWSER_CHANNEL:-msedge}"
export BASE_URL="http://localhost:${PORT}"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORK="$(mktemp -d)"

echo "== building reggraeton"
mvn -q -B -f "$ROOT/pom.xml" install -DskipTests

echo "== starting the demo shop on port $PORT"
java "$ROOT/samples/demo-app/DemoServer.java" "$PORT" "$ROOT/samples/demo-app/site" > "$WORK/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if curl -fs "$BASE_URL/login.html" > /dev/null; then break; fi
  sleep 1
done
curl -fs "$BASE_URL/login.html" > /dev/null || { echo "demo shop did not start"; cat "$WORK/server.log"; exit 1; }

status=0
for mode in bdd native; do
  project="$WORK/$mode"
  echo "== converting in $mode mode -> $project"
  "$ROOT/bin/reggraeton" convert "$ROOT/samples/legacy-shop" --out "$project" --mode "$mode"
  echo "== running the $mode tests (browser: $BROWSER_CHANNEL, headless: ${HEADLESS:-true})"
  (cd "$project" && mvn -B test > "$WORK/$mode.log" 2>&1) || true
  tests=$(cat "$project"/target/surefire-reports/TEST-*.xml | grep -o '<testcase ' | wc -l | tr -d ' ')
  failing=$(cat "$project"/target/surefire-reports/TEST-*.xml | grep -Eo '<(failure|error)[ >]' | wc -l | tr -d ' ')
  echo "   $tests tests, $failing failing"
  if [ "$tests" != "10" ] || [ "$failing" != "2" ]; then
    echo "   UNEXPECTED: wanted 10 tests with 2 failing by design. Log: $WORK/$mode.log"
    status=1
  fi
done

if [ $status -eq 0 ]; then
  echo "== OK: both modes ran 10 tests, 8 passed, 2 failed by design (work dir: $WORK)"
fi
exit $status
