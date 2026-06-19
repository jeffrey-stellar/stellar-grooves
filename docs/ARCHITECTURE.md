# Architecture & Contributor Orientation

A map of how Stellar Grooves fits together, so you can find the right file fast.
For the directory listing see [Project Structure](../README.md#project-structure)
in the README; this doc focuses on *how the pieces interact* and *where to make
a given change*.

## The shape of it

Stellar Grooves is a single Spring Boot application (Java 17) backed by MongoDB,
serving both a server-rendered UI (Thymeleaf) and a JSON REST API. The frontend
is vanilla JavaScript — one module per page under
`src/main/resources/static/js/`, sharing helpers via a `window.SG` namespace.
There is no SPA framework and no build step for the frontend beyond copying
vendored assets.

```
Browser
  │  (Thymeleaf pages + vanilla JS, or REST clients)
  ▼
Controllers (controller/)         ← HTTP entry points, request/response DTOs
  ▼
Services (service/)               ← business logic, orchestration
  ▼
Repositories (repository/)        ← Spring Data MongoDB
  ▼
MongoDB                           ← documents defined in model/
```

Cross-cutting concerns live in `security/` (authentication) and `config/`
(filters, startup validation, rate limiting, Mongo indexes, WebSocket).

## Request lifecycle

1. **Security filters** run first (`security/AuthTokenFilter` validates a JWT
   bearer token if present; Spring Security's form-login handles session auth for
   the UI). `config/RequestCorrelationFilter` tags each request with an id for
   logging; `config/RateLimitFilter` throttles `/api/v1/auth/**` and
   `/api/v1/shared/**`.
2. **A controller** in `controller/` handles the route. UI routes are in
   `ViewController` (returns Thymeleaf templates); everything else is a REST
   controller returning JSON.
3. **A service** in `service/` does the work and calls **repositories**.
4. **`GlobalExceptionHandler`** maps exceptions to consistent JSON error
   responses.

## Authentication & security

- **Two auth modes share one user store.** The UI uses Spring Security
  **form login** (session cookie); the REST API uses **JWT** bearer tokens.
  Config: `security/WebSecurityConfig`, tokens in `security/JwtUtils`,
  revocation via `security/TokenBlacklistService`.
- **CSRF** protects state-changing UI/session requests. `POST`/`PATCH` to
  `/api/v1/library/**` need the session cookie **and** the `X-XSRF-TOKEN`
  header (cookie `XSRF-TOKEN`). `/api/v1/auth/**`, `/api/v1/shared/**`, and
  `/ws/**` are CSRF-exempt.
- **Rate limiting** is pluggable: `config/RateLimitStore` with an in-memory
  default (`InMemoryRateLimitStore`) and an optional Redis-backed
  implementation (`RedisRateLimitStore`) for multi-instance deployments.
- `config/StartupValidator` fails fast on unsafe production config (e.g. missing
  `CORS_ALLOWED_ORIGINS` under the `prod` profile).

## Smart playlists — the query DSL

The headline feature. A smart playlist stores a **query string** (e.g.
`genre:thrash rating:>=4 sort:year:desc`) that is evaluated against a user's
library. The pipeline lives in `smartplaylist/`:

```
query string
  → SmartPlaylistQueryParser   → ParsedQuery / QueryExpr / QueryPredicate (AST)
  → PhraseExpander             → expands reusable named phrases
  → SmartPlaylistQueryTranslator → a MongoDB query (+ SortSpec)
```

- Parse errors throw `QueryParseException` (surfaced as a helpful API error).
- **Reusable phrases** (`model/SmartPlaylistPhrase`) let a user name a fragment
  and reuse it across playlists — expanded by `PhraseExpander`.
- Orchestration and persistence: `service/SmartPlaylistService`,
  controllers `SmartPlaylistController` / `SharedSmartPlaylistController` (public
  read-only sharing).

If you change the query language, you'll touch the parser, the translator, and
their tests under `src/test/java/.../smartplaylist/`.

## Data model

Documents are in `model/`; each has a repository in `repository/`. Notable ones:

- `MusicFile` — a scanned track. Has a single `genre` (`Genre` enum) plus
  `additionalGenres`. Cover art is **not** stored here — see below.
- `CoverArt` / `CoverArtMiss` — album art lives in its own `cover_art`
  collection (bytes), with misses cached to avoid re-fetching.
- `SmartPlaylist`, `SmartPlaylistPhrase`, `Playlist` — playlists (smart and
  manual) and reusable query phrases.
- `PlayEvent` — play history, powering Recently Played / Top Tracks and the
  Rediscovery features (`service/RediscoveryService`).
- `User`, `Role`, plus token documents (`RefreshToken`, `BlacklistedToken`,
  `PasswordResetToken`, `EmailVerificationToken`).

## Scanning & catalog

- `service/MusicScannerService` (+ helpers in `service/scan/`) walks configured
  directories, reads tags via JAudioTagger, and upserts `MusicFile` documents.
  `service/ScanPathValidator` enforces path-traversal/allowlist rules.
- `service/MusicCatalogService` auto-categorizes artists by genre from
  `src/main/resources/catalog.json` (a simple `artist → [genre]` map). Adding
  artists here is the most newcomer-friendly contribution — see the
  good-first-issues.

## Cover art

A three-phase pipeline (in `service/coverart/`): embedded art → a sidecar
folder image → an **opt-in** external fetch (MusicBrainz + iTunes, off by
default). The UI keys off `hasCoverArt` and a `/cover` endpoint. Art is stored
in MongoDB, never on disk.

## Frontend

- One JS module per page in `static/js/` (`app.js`, `smart-playlist.js`,
  `history.js`, `rediscover.js`, `queue.js`, `player.js`, `tags.js`, …).
- Shared utilities are in `helpers.js`, exposed via `window.SG` and (uniquely)
  also exported for tests via a dual `module.exports`/IIFE wrapper. Pure helpers
  that need tests should follow that pattern.
- Templates are Thymeleaf under `src/main/resources/templates/`.

## Tests

- **Backend:** JUnit + Testcontainers (a real MongoDB in Docker) under
  `src/test/java/`. `mvn verify` runs them with a **60% JaCoCo line-coverage
  gate**.
- **Frontend:** Vitest under `src/test/js/` (auto-discovered by
  `vitest.config.js`). Coverage here is thin and a good place to contribute.

## "Where do I change…?"

| I want to… | Look in |
| --- | --- |
| Add/fix an artist's genre | `src/main/resources/catalog.json` |
| Change the smart-playlist query language | `smartplaylist/` (parser + translator) |
| Add or change a REST endpoint | `controller/` + a `service/` + DTO in `dto/` |
| Add a UI page or behavior | `templates/` + a module in `static/js/` |
| Touch auth / login / tokens | `security/` + `config/WebSecurityConfig` |
| Change rate limiting | `config/RateLimit*` |
| Adjust cover-art fetching | `service/coverart/` |
| Change scanning behavior | `service/MusicScannerService`, `service/scan/` |
| Add a config setting | `src/main/resources/application*.properties` (env-overridable) |

See [`CONTRIBUTING.md`](../CONTRIBUTING.md) for setup, tests, the PR flow, and
the CLA.
