# Stellar Grooves — Public Demo

A self-contained, **frozen** instance for letting people try Stellar Grooves
before they self-host. It runs the real app against a small seeded library and
resets to a pristine state on a schedule, so visitors can click around (browse,
play with smart playlists, rate tracks) without anyone being able to break it.

This is a **try-it demo, not a hosting service** — by design it never stores
anyone's audio:

- Only a small set of **Creative-Commons demo tracks** is mounted (read-only);
  visitors can't add their own music, and anything a *Scan* picks up is wiped on
  the next reset.
- The seed ships **without album art**; covers are fetched at runtime from public
  providers, so **no copyrighted images live in this repo**.
- Whatever a visitor changes is **wiped on the next reset** (frozen via reset,
  not by locking the UI — everything stays fully interactive).

## What's in the seed

`seed/stellar-grooves-demo.archive.gz` (~24 KB, a `mongodump --gzip --archive`)
contains only:

- **249 tracks** across ~20 artists (a curated hard-rock / metal / thrash
  spread), with generic `/music/...` file paths (no real filesystem info).
- A spread of **ratings**, **play history** (Recently Played, Top
  Tracks/Artists), and Rediscover-friendly data (Forgotten Favorites,
  One-Hit-Wonders).
- **4 smart playlists** (Heavy Rotation, Forgotten Favorites, Thrash & Heavy,
  and **▶ Playable Demo Tracks** — the CC tracks that actually play, via
  `tag:playable`) plus one reusable phrase (`eighties-metal`).
- **6 playable Creative-Commons tracks** (HoliznaCC0's *Rock Montage*, CC0, and
  Kevin MacLeod's *Metalmania*, CC BY 4.0) whose audio ships in `audio/` so the
  jukebox actually plays. The rest of the catalog is browse-only. See
  [`audio/CREDITS.md`](audio/CREDITS.md).
- A single user — the demo account below. No personal data.

**Demo login:** `demo` / `GrooveDemo1`

The compose sets `DEMO_MODE=true`, which **disables self-signup** and makes the
sign-in page show and pre-fill these credentials — so visitors land on the
seeded account in one click instead of creating an empty one. (Override the
shown credentials with `DEMO_USERNAME` / `DEMO_PASSWORD` if you change them.)

## Run it

From the repo root:

```bash
cp demo/.env.example demo/.env      # then set JWT_SECRET (openssl rand -base64 32)
docker compose --env-file demo/.env -f demo/docker-compose.demo.yml up -d --build
./demo/scripts/reset-demo.sh        # seed the DB and warm cover art
```

The app is published on `127.0.0.1:8089` (override with `DEMO_PORT`) — reachable
on the host for testing and the reset script, but not public. For a real public
instance, use the bundled HTTPS below.

## Public HTTPS (one command)

The stack includes an optional **Caddy** reverse proxy that fetches and renews a
Let's Encrypt certificate automatically. To go live:

1. Point your domain's DNS **A record** at the host.
2. In `demo/.env` set `DEMO_DOMAIN` (e.g. `demo.stellargrooves.com`),
   `ACME_EMAIL`, and `CORS_ALLOWED_ORIGINS=https://<your-domain>`.
3. Bring it up with the `tls` profile (ports 80/443 must be open):

```bash
docker compose --env-file demo/.env -f demo/docker-compose.demo.yml --profile tls up -d --build
./demo/scripts/reset-demo.sh
```

Caddy serves `https://<your-domain>` and proxies to the app; certificates
persist in the `caddy-data` volume across restarts. For a local trial without a
domain, set `DEMO_DOMAIN=localhost` (Caddy uses a self-signed internal CA).

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
