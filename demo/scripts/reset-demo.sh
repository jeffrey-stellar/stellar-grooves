#!/usr/bin/env bash
#
# Reset the Stellar Grooves public demo to its pristine seeded state.
#
# Run this once after `docker compose ... up`, and again on a schedule (e.g. a
# nightly host cron job) to wipe whatever visitors changed during the day:
#
#   0 4 * * * cd /path/to/stellar-grooves && ./demo/scripts/reset-demo.sh >> /var/log/sg-demo-reset.log 2>&1
#
# What it does:
#   1. Drops the demo database and restores the committed seed archive.
#   2. Waits for the app to be reachable.
#   3. Warms album art by triggering the runtime cover-art fetch (the seed ships
#      without embedded covers, so no copyrighted images live in the repo).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

COMPOSE_FILE="demo/docker-compose.demo.yml"
ENV_FILE="demo/.env"
DB="stellar_grooves_demo"
ARCHIVE="/seed/stellar-grooves-demo.archive.gz"

# Pick up DEMO_PORT / contact from demo/.env if present (so cron needs no env).
if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi

DEMO_PORT="${DEMO_PORT:-8089}"
DEMO_USER="${DEMO_USER:-demo}"
DEMO_PASS="${DEMO_PASS:-GrooveDemo1}"
BASE="http://localhost:${DEMO_PORT}"

DC="docker compose"
[ -f "$ENV_FILE" ] && DC="$DC --env-file $ENV_FILE"
DC="$DC -f $COMPOSE_FILE"

echo "[reset] $(date -u +%FT%TZ) dropping + restoring '$DB' from seed…"
$DC exec -T mongo mongosh --quiet "$DB" --eval 'db.dropDatabase()' >/dev/null
$DC exec -T mongo mongorestore --quiet --gzip --archive="$ARCHIVE" >/dev/null
echo "[reset] seed restored."

# Keep the demo evergreen: the seed's play dates are fixed at build time, but the
# smart playlists use relative windows (e.g. "lastPlayed:<14d", "lastPlayed:>6mo").
# Shift every play date forward by (now - seededAt) so those playlists keep
# returning results no matter how long ago the seed was built. demo_meta.seededAt
# is stamped when the seed is generated; it is restored fresh each reset, so the
# delta is always measured from the original build date (idempotent).
echo "[reset] re-stamping play dates so relative-date playlists stay fresh…"
$DC exec -T mongo mongosh --quiet "$DB" --eval '
  const m = db.demo_meta.findOne({_id:"seed"});
  if (m && m.seededAt) {
    const delta = Date.now() - new Date(m.seededAt).getTime();
    if (delta > 0) {
      db.music_files.updateMany({lastPlayedAt:{$exists:true,$ne:null}},
        [{$set:{lastPlayedAt:{$add:["$lastPlayedAt", delta]}}}]);
      db.play_events.updateMany({}, [{$set:{playedAt:{$add:["$playedAt", delta]}}}]);
    }
  }
' >/dev/null
echo "[reset] play dates re-stamped."

echo "[reset] waiting for app on ${BASE}…"
for _ in $(seq 1 60); do
  if curl -sf "$BASE/login" >/dev/null 2>&1; then break; fi
  sleep 2
done

# Warm cover art via the authenticated runtime fetch (session cookie + CSRF,
# exactly like the browser). Skips cleanly if the endpoint is unavailable.
echo "[reset] warming album art…"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
curl -s -c "$JAR" "$BASE/login" -o /dev/null || true
XSRF="$(awk '/XSRF-TOKEN/{print $7}' "$JAR")"
curl -s -b "$JAR" -c "$JAR" -o /dev/null \
  -d "username=${DEMO_USER}&password=${DEMO_PASS}&_csrf=${XSRF}" "$BASE/login" || true
XSRF="$(awk '/XSRF-TOKEN/{print $7}' "$JAR")"
code="$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' -X POST \
  -H "X-XSRF-TOKEN: ${XSRF}" "$BASE/api/v1/library/cover-art/fetch" || echo 000)"
case "$code" in
  202) echo "[reset] cover-art fetch started (covers populate in the background, ~1 min)." ;;
  409) echo "[reset] cover-art fetch already running — skipping." ;;
  403) echo "[reset] cover-art fetch disabled on this server — skipping (covers will show placeholders)." ;;
  *)   echo "[reset] WARN: cover-art fetch request returned HTTP ${code}." ;;
esac

echo "[reset] done."
