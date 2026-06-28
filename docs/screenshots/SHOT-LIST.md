# Screenshot shot list

This file tells you exactly what to capture for the README. Each entry lists
the **target filename**, what to show, and the recommended window size.

## Capture rules (apply to every shot)

- **Window size: 1440 × 900** — keep it consistent so the images don't jitter
  in size when stacked. macOS: ⌘+Option+I in DevTools to dimension the window,
  or use [Rectangle](https://rectangleapp.com) presets.
- **Theme: light** (the "screen-print" theme) for the current README hero set —
  toggle with the navbar sun/moon and confirm `data-theme="light"` before shooting.
  Keep a shot in dark if you're specifically showing the dark jukebox idiom.
- **Real data, not placeholders.** Use a 100+ track demo library so virtual
  scrolling, cover art grids, and sort headers actually have content.
- **No personal info.** No email addresses, no real usernames. Sign in as a
  user named `demo` or similar before capturing.
- **PNG, not JPG.** UI screenshots compress poorly as JPG.
- **Trim browser chrome** — capture just the app viewport (CleanShot's
  "Capture Window" or Kap's window picker does this; on macOS you can also
  use ⌘+Shift+4 then Space and click the browser content area).

## Required shots (referenced in README.md)

### `library-overview.png` (hero)

The primary "what is this app" shot. Main library view with sidebar visible,
10–15 tracks across mixed artists/albums, at least 3 cover art thumbnails,
filter dropdowns at "All". First image visitors see in the README.

### `smart-playlist-editor.png`

The differentiator shot. Smart Playlists view with the editor open on a real
query (e.g. `genre:hard_rock rating:>=4 lastPlayed:>1y sort:random limit:25`)
and the preview / match-count panel visible.

### `jukeplayer.png`

The persistent player bar with a track loaded — album art, title/artist,
seek bar, volume, queue indicator. The retro jukebox theme is a key visual
identity beat.

### `rediscover-playlists.png`

The Listening Rediscovery view: forgotten tracks, neglected favorites, and
one-hit-wonders, all rendered. Curator-focused feature that distinguishes
the app from generic music libraries.

### `listening-history.png`

The Listening History view with the Recently Played / Top Tracks / Top
Artists tabs visible — ideally with the time-window selector showing.

### `mobile-pwa.png`

Browser viewport at **390 × 844** (iPhone 14 size) showing the responsive
library view with the player bar adapted for narrow screens. Use Chrome
DevTools' device toolbar.

### `login-screen.png`

The login form. Don't show real account names — use `demo` or similar.

## Optional shots (not currently in README)

### `library-light.png`

Same composition as `library-overview.png` but with the light theme toggled.
Demonstrates that light mode exists and is intentional, not an afterthought.

### `album-grid.png`

Album-grid view (toggle from list view). ~12–20 albums in the grid. Could
replace any current grid entry if cover-art-at-scale becomes the headline
visual.

### `admin-dashboard.png`

Admin panel showing user list + per-user stats. Only relevant if you want to
highlight multi-user as a feature in the README.

## Screencast (optional, not currently in README)

A 20–40 second GIF/MP4 demonstrating the **shared-query flow** end-to-end —
curator writes a query, copies the share URL, second user pastes and
subscribes, shows different tracks (because the query runs against the
subscriber's own library). This is the keystone differentiator and is
worth recording once you have time.

Recommended tools (macOS):

- **[Kap](https://getkap.co)** — free, exports GIF directly. Best for short clips.
- **[CleanShot X](https://cleanshot.com)** — paid, smaller output files, better quality.
- **[Gifski](https://gifski.app)** — free, converts MP4 → GIF if you record with QuickTime.

Target file size: **under 8 MB** for GitHub README rendering. If larger,
host on YouTube and embed a linked thumbnail (GitHub READMEs don't render
`<video>` tags, but they do render linked images).
