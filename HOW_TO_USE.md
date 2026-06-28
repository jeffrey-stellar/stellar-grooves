# How to Use Stellar Grooves

Stellar Grooves is a self-hosted music library for rock and metal collections. This guide walks through the everyday browser flow: signing up, scanning a music directory, and using the curator-focused features (smart playlists, phrases, sharing).

> The app is built and themed for rock and metal, but every feature below works with any collection — only the genre auto-classification is rock/metal-specific (other music imports fully and lands under "Other"; organize it with tags, ratings, and artist/album smart playlists).

For installation and configuration, see [`README.md`](README.md). This document assumes the app is already running at `http://localhost:8089`.

---

## 1. Create your account

1. Open <http://localhost:8089/signup>.
2. Pick a username, enter your email, and choose a password (min 8 characters, with at least one uppercase letter, one lowercase letter, and one digit).
3. If email verification is enabled (`EMAIL_VERIFICATION_REQUIRED=true`), check your inbox for a verification link before logging in.
4. Log in at <http://localhost:8089/login>.

> Form login uses a session cookie + CSRF token automatically. You only need a JWT if you're calling the API from a script or a non-browser client — see the API section in `README.md`.

## 2. Scan your music

From the main page:

1. In the **Scan** panel on the left, paste the absolute path to a directory containing audio files (e.g. `/Users/you/Music`).
2. Click **Start Scan**.
3. Watch the live progress bar — the scanner streams updates over Server-Sent Events. You'll see files imported, skipped (duplicates), and any errors.

The scanner:

- Recurses into subdirectories (configurable depth, default 20).
- Reads `.mp3`, `.flac`, and `.m4a` by default — extend with `SCAN_SUPPORTED_EXTENSIONS`.
- Extracts artist, album, title, year, and cover art via JAudioTagger — embedded artwork first, then a sidecar image (`cover.jpg`, `folder.jpg`, `front.jpg`, `albumart.jpg`…) next to the track when none is embedded.
- Computes a SHA-256 hash of each file for exact-duplicate detection.
- Auto-classifies tracks against the bundled artist→genre catalog (Classic Rock, Hard Rock, Hair Metal, Heavy Metal, Thrash Metal). Anything unmatched lands as **Other**.
- Skips files already imported by path or by `(title, artist)`.

Per-user cooldown prevents rapid re-scans (default 60s). Per-user concurrency lock keeps simultaneous scans from racing. To schedule recurring scans, use the **Schedule** field on the same panel — it accepts a Spring cron expression (e.g. `0 0 3 * * *` for 3 AM daily).

## 3. Browse and play

Tracks land in the main library table. From there you can:

- **Filter** by artist, album, or genre using the dropdowns (all combine with AND).
- **Search** with the full-text search box — title, artist, and album are weighted.
- **Sort** by clicking column headers (title, artist, album, genre, year, rating, last played).
- **Switch views**: list view or album-grid view (toggle in the toolbar). Album grid shows cover art thumbnails.
- **Set cover art**: hover an album in the grid and click the 📷 button to upload or replace its cover from an image file (JPEG, PNG, WebP, GIF, or BMP). The chosen image applies to every track on that album.
- **Fetch missing art online** *(only if the admin has enabled it)*: a **Fetch missing art** button in the album toolbar looks up covers for albums that don't have any, using MusicBrainz and iTunes. It asks for confirmation first, because it sends your album and artist names to those services.
- **Drill down** by clicking an artist or album row to filter to that scope.
- **Rate** tracks with the 5-star widget on each row. Sort by rating to find favorites.
- **Tag** tracks with custom labels (e.g. `live`, `acoustic`, `road-trip`). Tags are listed in the **Tags** sidebar entry with usage counts.
- **Edit genre** inline if a track was misclassified — this records a correction for that artist, so future scans use the new genre.
- **Play** by clicking the row's play button. The persistent player bar at the bottom handles seek, volume, shuffle, queue, and crossfade.

Keyboard: Space (play/pause), ←/→ (seek), ↑/↓ (volume) — active when no input is focused.

## 4. Smart Playlists

Smart playlists are saved queries — when you open one, it runs against your library and shows whatever currently matches. Open the **Smart Playlists** sidebar entry, click **+ New Smart Playlist**, give it a name, and write a query.

### Query syntax — the basics

```
genre:hard_rock
artist:"led zeppelin"
year:>=1970
rating:>=4
playCount:0
tag:road-trip
lastPlayed:<7d
lastPlayed:>1mo
year:1970..1979
```

- **Text fields** (`artist:`, `album:`, `title:`) match by case-insensitive contains. Quote multi-word values: `artist:"black sabbath"`.
- **Genre values** are the enum names: `classic_rock`, `hard_rock`, `hair_metal`, `heavy_metal`, `thrash_metal`, `other`.
- **Numeric fields** (`year:`, `rating:`, `playCount:`) accept `=`, `>`, `>=`, `<`, `<=`, and `low..high` ranges.
- **Last played** windows: `lastPlayed:<7d` (played in the last 7 days), `lastPlayed:>1mo` (not played in the last month). Units: `d`, `w`, `mo`, `y`.
- **Negation**: prefix with `-` (e.g. `-genre:other`).
- **Boolean composition**: `AND` (default between clauses), `OR` / `||`, parentheses for grouping.
- **Sort & limit** (top-level only):
  - `sort:rating:desc`, `sort:year:asc`, `sort:lastPlayed:desc`, `sort:random`
  - `limit:50`
  - `sort:random` requires a `limit:`.

### Example queries

Recent rotation:
```
lastPlayed:<14d sort:lastPlayed:desc
```

Forgotten classics:
```
genre:classic_rock rating:>=4 lastPlayed:>1y sort:random limit:25
```

Eighties metal road-trip mix:
```
(genre:hair_metal OR genre:heavy_metal) year:1980..1989 tag:road-trip
```

### Preview, save, and use

- **Preview** runs the query without saving — handy while you're tuning.
- **Match count** shows how many tracks the query currently returns.
- **Save** persists the query. Opening it later runs it again against your live library.
- **Materialize** snapshots the current matches into a regular (static) playlist if you want a frozen copy for export or sharing audio.

## 5. Phrases — reusable query fragments

Phrases let you name a fragment of DSL once and reuse it. Open the **Phrases** sidebar entry to manage them.

Define a phrase named `road-trip` with body:
```
(genre:hair_metal OR genre:heavy_metal) year:1980..1989
```

Then reference it from any smart playlist with `@road-trip`:
```
@road-trip rating:>=4 sort:random limit:30
```

A phrase body is itself a query, so phrases can compose phrases. The expander has cycle detection, max recursion depth of 8, and a max of 64 expansions per query — bad references surface as parser errors when you save.

> Phrase names cannot be renamed in this release — referring smart playlists would silently break. Create a new phrase if you need a different name; old ones stick around until you delete them explicitly.

## 6. Sharing smart playlists

Smart playlists are shareable as queries — not as audio. When User A shares a playlist with User B, what gets shared is the **query**; User B's app runs it against User B's own library. This means User B only sees tracks they actually own that match the curator's criteria.

From a smart playlist's view:

- **Share**: generates a public link (`/shared/smart-playlists/{token}`). Anyone with the link can preview the query and the curator's match count.
- **Subscribe**: another logged-in user pastes the link, hits subscribe, and the playlist appears in their sidebar. It re-evaluates against *their* library — if they have no Iron Maiden, they see no Iron Maiden.
- **Fork**: copy the query into a new smart playlist owned by the subscriber. Forks are independent — the curator's later edits don't propagate. If the source has been deleted, forking falls back to the latest known query body.
- **Revoke**: the share link stops working; existing subscribers retain their cached copy until they refresh.

Subscriber count is visible to the curator. If the curator deletes the playlist, subscribers see a "source deleted" badge but their copy keeps working until they fork or unsubscribe.

> Phrases referenced in a shared query expand against the **curator's** phrase library, not the subscriber's. Renaming or deleting a curator-side phrase will break subscribers' queries — that's why phrase renames are blocked.

## 7. Regular playlists

For curated, hand-ordered sets of tracks (or for snapshotting a smart-playlist result):

- Create a playlist from the sidebar.
- Add tracks via the **+** button on each library row, or bulk-select rows and use **Add to playlist**.
- Drag rows to reorder. Sort by any column.
- Export as **M3U** or **JSON**.
- Share with a read-only link (`/shared/playlists/{token}`) that lists the playlist's tracks with metadata only — no audio served to non-owners.

## 8. Listening history & rediscovery

Stellar Grooves records a play event when a track reaches 50% of its duration or plays to completion. Seeking is detected and excluded — listening time is wall-clock vs. playback-position.

- **Listening History** sidebar: Recently Played, Top Tracks, Top Artists, with a time-window selector (All Time / 7 Days / 30 Days / Last Year). Top Artists rows drill into the artist's albums.
- **Rediscover** sidebar: pre-built queries to surface tracks you haven't played in a while.
  - **Forgotten** — high-rated tracks not played recently.
  - **Neglected favorites** — high-rated tracks with low play counts.
  - **One-hit wonders** — artists you've played exactly one track from.

## 9. Maintenance

- **Trash**: deleted tracks soft-delete to a 30-day trash. Restore individual tracks or empty trash entirely. After 30 days, a scheduled job purges them.
- **Duplicates**: dedicated views for duplicate `(title, artist)` groups and for exact file-hash matches across folders. Resolve duplicates by deleting the copies you don't want.
- **Backup**: export your full library (tracks + ratings + tags + playlists + smart playlists + phrases + genre corrections + file hashes) as one JSON file. Restore it on any instance to recreate everything except the audio files themselves.
- **Library export**: lighter exports as JSON or CSV from the toolbar.

## 10. Light vs. dark mode

Sun/moon button in the navbar toggles between the dark jukebox theme and the light "screen-print" theme. Preference is saved per device and respects `prefers-color-scheme` on first visit.

## 11. Admin

Admin users can open <http://localhost:8089/admin> for:

- System stats (total users, files, playlists).
- Paginated user list with per-user file counts.
- Delete a user — cascades to their files, playlists, queue, play history, and cover art.

To create an admin on first start, set `ADMIN_PASSWORD` (and optionally `ADMIN_USERNAME`, `ADMIN_EMAIL`) when launching the app.

## 12. Help & API docs

- Built-in user guide: <http://localhost:8089/help> (no login required, also linked from login/signup pages).
- Interactive API docs: <http://localhost:8089/swagger-ui.html> (dev profile only).
- OpenAPI spec: `/api-docs`.

For full endpoint reference, profile configuration, and deployment notes, see [`README.md`](README.md).
