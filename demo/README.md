# Stellar Grooves — Public Demo

A self-contained, **frozen** instance for letting people try Stellar Grooves
before they self-host. It runs the real app against a small seeded library and
resets to a pristine state on a schedule, so visitors can click around (browse,
play with smart playlists, rate tracks) without anyone being able to break it.

This is a **try-it demo, not a hosting service** — by design it never stores
anyone's audio:

- **No music directory is mounted**, so a visitor's *Scan* finds nothing.
- The seed ships **without album art**; covers are fetched at runtime from public
  providers, so **no copyrighted images live in this repo**.
- Whatever a visitor changes is **wiped on the next reset** (frozen via reset,
  not by locking the UI — everything stays fully interactive).

## What's in the seed

`seed/stellar-grooves-demo.archive.gz` (~23 KB, a `mongodump --gzip --archive`)
contains only:

- **243 tracks** across **18 artists** (a curated hard-rock / metal / thrash
  spread), with generic `/music/...` file paths (no real filesystem info).
- A spread of **ratings**, **play history** (58 play events → Recently Played,
  Top Tracks/Artists), and Rediscover-friendly data (Forgotten Favorites,
  One-Hit-Wonders).
- **3 smart playlists** (Heavy Rotation, Forgotten Favorites, Thrash & Heavy)
  and one reusable phrase (`eighties-metal`).
- A single user — the demo account below. No personal data.

**Demo login:** `demo` / `GrooveDemo1`

## Run it

From the repo root:

```bash
cp demo/.env.example demo/.env      # then set JWT_SECRET (openssl rand -base64 32)
docker compose --env-file demo/.env -f demo/docker-compose.demo.yml up -d --build
./demo/scripts/reset-demo.sh        # seed the DB and warm cover art
```

The demo is then on `http://<host>:8089` (override with `DEMO_PORT`). Put it
behind a reverse proxy / TLS for a real public instance.

## Keep it frozen (nightly reset)

`reset-demo.sh` drops the demo DB, restores the pristine seed, and re-warms the
album art. Schedule it (host crontab), e.g. 4am daily:

```cron
0 4 * * * cd /path/to/stellar-grooves && ./demo/scripts/reset-demo.sh >> /var/log/sg-demo-reset.log 2>&1
```

Between resets the demo is fully live and shared — visitors see each other's
edits until the nightly wipe. That's the accepted trade for the simplest freeze;
if abuse becomes an issue, the next step up is per-visitor sandboxes.

## Cover art

The seed has no embedded covers. On each reset the script triggers the app's
opt-in external cover-art fetch (`COVER_ART_EXTERNAL_ENABLED=true`), which pulls
art from MusicBrainz / iTunes into the running DB (~18 of 21 albums resolve, in
about a minute). The art lives only in the running container and is re-fetched
after every reset — it is never committed here.

## Regenerating the seed

The seed is built from a real library on the maintainer's machine, then
sanitized (generic paths, single demo user, art stripped) and dumped. See the
`reference_screenshots` notes for the capture/seed harness. To refresh it:

```bash
# after rebuilding the demo DB clean + sanitizing:
mongodump --db=stellar_grooves_demo --gzip \
  --archive=demo/seed/stellar-grooves-demo.archive.gz
```
