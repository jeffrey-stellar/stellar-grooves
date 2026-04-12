# Stellar Grooves

A self-hosted, multi-user music library for rock and metal collections. Scan local directories for audio files, auto-categorize tracks by sub-genre, manage playlists, and stream everything in the browser with a retro jukebox-themed UI.

Built with Spring Boot, MongoDB, and vanilla JavaScript.

---

## Features

- **Directory scanning** — recursively finds `.mp3`, `.flac`, and `.m4a` files and extracts metadata (artist, album, title, year) via JAudioTagger; configurable scan depth limit; symlink-safe
- **Auto genre classification** — customizable JSON catalog of 80+ rock/metal artists spanning the 1960s-2020s, mapping to Classic Rock, Hard Rock, Hair Metal, Heavy Metal, and Thrash Metal
- **Duplicate detection** — skips files already imported by both file path and metadata (title + artist)
- **In-browser playback** — persistent audio player bar with play/pause, seek, and volume controls; HTTP Range support for seeking in large files; auto-advances to the next track when a song ends
- **Playlist management** — create playlists, add/remove tracks, browse playlist contents
- **Browse & filter** — drill-down views by artist, album, and genre; full-text search across title/artist/album; sortable columns
- **Inline genre editing** — reclassify any track tagged as "Other" directly from the library table
- **Pagination** — paginated API responses for large libraries (`?page=0&size=50`, max 200 per page)
- **Multi-user** — per-user libraries with session-based (form login) and JWT authentication
- **Security** — CSRF protection, rate limiting on auth endpoints (with proxy-aware IP detection), configurable CORS origins, path traversal prevention, server-side input validation with typed DTOs, Content Security Policy headers, password complexity requirements
- **Structured logging** — correlation IDs on every request (`X-Correlation-Id` header), MDC-based log pattern for request tracing
- **Spring profiles** — `dev` (debug logging, no template cache) and `prod` (strict CORS, proxy trust) profiles
- **Admin bootstrap** — auto-create an admin user on first startup via environment variables
- **Health check** — `/actuator/health` endpoint for monitoring (includes MongoDB connectivity)
- **Admin panel** — admin endpoints for user management and cleanup (with pagination)
- **Accessibility** — ARIA labels on interactive elements, table scope attributes, `prefers-reduced-motion` support
- **Jukebox theme** — retro dark UI with neon glow effects, chrome accents, wood grain textures, and "Righteous" display typography

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java JDK | 17+ | [Adoptium Temurin](https://adoptium.net) recommended |
| Apache Maven | 3.6+ | Included via `mvnw` wrapper — no separate install needed |
| MongoDB | 6.0+ | Must be running before the app starts |

### Installing MongoDB

**macOS (Homebrew)**
```bash
brew tap mongodb/brew
brew install mongodb-community
brew services start mongodb-community
```

**Ubuntu / Debian**
```bash
sudo apt-get install -y mongodb
sudo systemctl start mongodb
```

**Windows**

Download the MSI installer from [mongodb.com/try/download/community](https://www.mongodb.com/try/download/community), then start the service:
```powershell
net start MongoDB
```

No additional database setup is needed — MongoDB creates the database and collections automatically on first run.

---

## Quick Start

A `JWT_SECRET` environment variable is **required** to start the app. Generate one with:

```bash
export JWT_SECRET=$(openssl rand -base64 64)
```

Then run:

```bash
git clone <repo-url>
cd stellar-grooves

# Run with Maven (development)
JWT_SECRET=$JWT_SECRET ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Windows:
```powershell
$env:JWT_SECRET = [Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Max 256 }) -as [byte[]])
mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The app starts at **http://localhost:8080**.

### Build a JAR (production)

```bash
./mvnw clean package -DskipTests
JWT_SECRET=$JWT_SECRET java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Run tests

```bash
./mvnw test
```

---

## Configuration

All settings live in `src/main/resources/application.properties` and can be overridden via environment variables.

### Core Settings

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `spring.data.mongodb.uri` | `MONGO_URI` | `mongodb://localhost:27017/stellar_grooves` | MongoDB connection string |
| `stellar.grooves.jwtSecret` | `JWT_SECRET` | *(none — required)* | Base64-encoded JWT signing secret (minimum 256 bits). App **fails to start** without this. |
| `stellar.grooves.jwtExpirationMs` | `JWT_EXPIRATION_MS` | `86400000` (24h) | JWT token lifetime in milliseconds |
| `server.port` | `PORT` | `8080` | HTTP listen port |

### Security & Rate Limiting

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `stellar.grooves.cors.allowedOrigins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:8080,http://127.0.0.1:8080` | Comma-separated CORS origin patterns |
| `stellar.grooves.rateLimit.maxRequests` | — | `10` | Max auth requests per IP per window |
| `stellar.grooves.rateLimit.windowMs` | — | `60000` (1 min) | Rate limit window in milliseconds |
| `stellar.grooves.rateLimit.trustProxy` | `RATE_LIMIT_TRUST_PROXY` | `false` | Trust `X-Forwarded-For` header for client IP detection |
| `stellar.grooves.rateLimit.trustedProxies` | `RATE_LIMIT_TRUSTED_PROXIES` | *(empty)* | Comma-separated proxy IPs allowed to set `X-Forwarded-For` (only used when `trustProxy=true`) |

### Scanner

| Property | Default | Description |
|----------|---------|-------------|
| `stellar.grooves.scan.maxDepth` | `20` | Max directory depth for recursive scan |
| `stellar.grooves.catalogPath` | *(bundled catalog.json)* | Path to a custom artist-genre catalog JSON file |

### Spring Profiles

| Profile | Activate with | Description |
|---------|--------------|-------------|
| `dev` | `--spring.profiles.active=dev` | Debug logging, Thymeleaf cache disabled, CORS allows `localhost:8080` |
| `prod` | `--spring.profiles.active=prod` | INFO logging, requires `CORS_ALLOWED_ORIGINS` env var, trusts proxy headers from configured IPs |

When no profile is active, the base `application.properties` defaults apply.

**Example — production deployment:**
```bash
MONGO_URI=mongodb://mongo-host:27017/grooves \
JWT_SECRET=$(openssl rand -base64 64) \
PORT=9090 \
CORS_ALLOWED_ORIGINS=https://myapp.example.com \
RATE_LIMIT_TRUST_PROXY=true \
RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1,::1 \
ADMIN_PASSWORD=changeme \
java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

> **Security note:** `JWT_SECRET` is required. Generate a strong Base64-encoded key (minimum 32 bytes decoded) with `openssl rand -base64 64`.

### Admin User

On first startup, you can create an admin user via environment variables:

```bash
ADMIN_PASSWORD=changeme java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar
```

| Env var | Default | Description |
|---------|---------|-------------|
| `ADMIN_PASSWORD` | *(none — required)* | Password for the initial admin user |
| `ADMIN_USERNAME` | `admin` | Username for the admin user |
| `ADMIN_EMAIL` | `admin@stellargrooves.local` | Email for the admin user |

The admin is only created if no admin user already exists. If a user with the given username exists but lacks the admin role, they are promoted.

### Custom Artist Catalog

The artist-genre mapping is stored in `src/main/resources/catalog.json`. To customize without recompiling, create your own JSON file and point to it:

```bash
stellar.grooves.catalogPath=/path/to/my-catalog.json \
java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar
```

The JSON format maps artist names to arrays of genre values:
```json
{
  "Artist Name": ["CLASSIC_ROCK", "HARD_ROCK"],
  "Another Band": ["THRASH_METAL"]
}
```

---

## First Use

1. Open **http://localhost:8080/signup** and create an account.
2. Log in at **http://localhost:8080/login**.
3. Enter the absolute path to a music directory (e.g. `/home/user/Music`) and click **Start Scan**.
4. Tracks appear in the library. Click the play button on any row to start streaming.
5. When a song ends, the next track plays automatically — jukebox style.
6. Create playlists from the sidebar and add tracks via the "+" button on each row.

---

## Health Check

A health endpoint is available at `/actuator/health` (no authentication required). It reports the overall application status including MongoDB connectivity.

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## REST API

All endpoints under `/api/library/*`, `/api/playlists/*`, and `/api/admin/*` require authentication. Use the session cookie from form login, or pass a JWT via the `Authorization: Bearer <token>` header (obtained from `/api/auth/signin`).

Session-authenticated requests (form login) must include a `X-XSRF-TOKEN` header with the value from the `XSRF-TOKEN` cookie for any mutating request (POST, PATCH, DELETE).

Auth endpoints are rate-limited to 10 requests per minute per IP by default.

### Auth

| Method | Endpoint | Body | Description |
|--------|----------|------|-------------|
| `POST` | `/api/auth/signup` | `{ "username", "email", "password" }` | Register a new user (password: min 8 chars, requires upper + lower + digit) |
| `POST` | `/api/auth/signin` | `{ "username", "password" }` | Log in; returns `{ token, username }` |

### Library

| Method | Endpoint | Body | Description |
|--------|----------|------|-------------|
| `GET` | `/api/library/files` | — | List tracks (paginated); `?page=0&size=50` (default); optional `?genre=HARD_ROCK` filter; max 200 per page |
| `POST` | `/api/library/scan` | `{ "path": "/absolute/path" }` | Scan a directory for audio files |
| `GET` | `/api/library/files/{id}/stream` | — | Stream audio (supports HTTP Range) |
| `PATCH` | `/api/library/files/{id}/genre` | `{ "genre": "CLASSIC_ROCK" }` | Update a track's genre |
| `DELETE` | `/api/library/files/{id}` | — | Delete a single track |
| `DELETE` | `/api/library/files` | — | Clear the current user's entire library |

The response is always a paginated object:
```json
{
  "content": [...],
  "page": 0,
  "size": 50,
  "totalElements": 342,
  "totalPages": 7
}
```

### Playlists

| Method | Endpoint | Body | Description |
|--------|----------|------|-------------|
| `GET` | `/api/playlists` | — | List all playlists with track counts |
| `POST` | `/api/playlists` | `{ "name": "My Playlist" }` | Create a new playlist (max 80 chars) |
| `DELETE` | `/api/playlists/{id}` | — | Delete a playlist |
| `GET` | `/api/playlists/{id}/tracks` | — | Get tracks in a playlist |
| `POST` | `/api/playlists/{id}/tracks` | `{ "fileId": "..." }` | Add a track to a playlist |
| `DELETE` | `/api/playlists/{id}/tracks/{fileId}` | — | Remove a track from a playlist |

### Admin (requires `ROLE_ADMIN`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/admin/users` | List all users; optional `?page=0&size=25` for pagination |
| `GET` | `/api/admin/users/{id}` | Get a single user |
| `DELETE` | `/api/admin/users/{id}` | Delete a user and all their data |

**Valid genre values:** `CLASSIC_ROCK`, `HARD_ROCK`, `HAIR_METAL`, `HEAVY_METAL`, `THRASH_METAL`, `OTHER`

---

## Project Structure

```
src/main/java/com/stellarideas/grooves/
├── StellarGroovesApplication.java       # Entry point
├── config/
│   ├── AdminBootstrap.java              # Auto-create admin on first startup
│   ├── RateLimitFilter.java             # Per-IP rate limiting (proxy-aware)
│   └── RequestCorrelationFilter.java    # MDC correlation ID for request tracing
├── controller/
│   ├── BaseController.java              # Shared getCurrentUser() logic
│   ├── AuthController.java              # Signup/signin endpoints
│   ├── LibraryController.java           # Library CRUD + streaming + pagination
│   ├── PlaylistController.java          # Playlist management
│   ├── AdminController.java             # Admin user management (paginated)
│   ├── ViewController.java              # Thymeleaf page routes
│   └── GlobalExceptionHandler.java      # Centralized error handling
├── model/
│   ├── User.java                        # User document (@JsonIgnore on password)
│   ├── MusicFile.java                   # Track document (compound indexes)
│   ├── Playlist.java                    # Playlist document (indexed)
│   ├── Genre.java                       # Genre enum
│   └── Role.java                        # Role enum
├── dto/
│   ├── AddTrackRequest.java             # Add track to playlist request
│   ├── CreatePlaylistRequest.java       # Create playlist request (validated)
│   ├── LoginRequest.java                # Login request validation
│   ├── MusicFileDTO.java                # Music file response DTO
│   ├── PlaylistDTO.java                 # Playlist response DTO
│   ├── ScanRequest.java                 # Directory scan request
│   ├── SignupRequest.java               # Signup request (with password policy)
│   └── UpdateGenreRequest.java          # Genre update request
├── repository/                          # Spring Data MongoDB repositories
├── security/
│   ├── WebSecurityConfig.java           # Security filter chain + CSRF + CORS + CSP
│   ├── AuthTokenFilter.java             # JWT extraction filter
│   ├── JwtUtils.java                    # Token generation/validation (no defaults)
│   ├── UserDetailsImpl.java             # Spring Security adapter
│   └── UserDetailsServiceImpl.java      # User loading service
└── service/
    ├── MusicCatalogService.java         # Artist -> genre mapping (JSON catalog)
    └── MusicScannerService.java         # Directory scanning + batch import

src/main/resources/
├── application.properties               # Shared configuration
├── application-dev.properties           # Dev profile (debug logging, no cache)
├── application-prod.properties          # Prod profile (strict CORS, proxy trust)
├── logback-spring.xml                   # Logging config with correlation IDs
├── catalog.json                         # Artist-genre catalog (customizable)
├── static/css/main.css                  # Jukebox theme stylesheet (CSP-compliant)
├── static/js/app.js                     # Frontend application logic
└── templates/                           # Thymeleaf HTML templates
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.2.3 |
| Persistence | Spring Data MongoDB |
| Security | Spring Security + JJWT 0.11.5 |
| Monitoring | Spring Boot Actuator |
| Templating | Thymeleaf + Bootstrap 5.3 |
| Audio metadata | JAudioTagger 3.0.1 |
| Build | Maven 3 |
| Runtime | Java 17 |
| Testing | JUnit 5 + Mockito |

---

## Native Installer (Optional)

Requires `jpackage` (bundled with JDK 14+). Build the JAR first, then:

**macOS**
```bash
jpackage --type app-image \
  --input target/ \
  --main-jar stellar-grooves-0.0.1-SNAPSHOT.jar \
  --main-class com.stellarideas.grooves.StellarGroovesApplication \
  --name "StellarGrooves"
```

**Windows**
```powershell
jpackage --type exe `
  --input target/ `
  --main-jar stellar-grooves-0.0.1-SNAPSHOT.jar `
  --main-class com.stellarideas.grooves.StellarGroovesApplication `
  --name "StellarGrooves" `
  --win-shortcut --win-menu
```

> MongoDB must still be running on the target machine.

---

## License

MIT
