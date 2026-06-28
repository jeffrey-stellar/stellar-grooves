# Remote & Mobile Access

How to reach your Stellar Grooves library from your phone or another device —
on your home network or anywhere — without exposing it to the public internet.

There are two paths:

- **[Tailscale (recommended)](#tailscale-recommended)** — a private, encrypted
  connection with real HTTPS. This is the only path where the installable PWA
  (Add to Home Screen, offline support, lock-screen controls) works fully,
  because service workers require a secure (HTTPS) context.
- **[Plain LAN over HTTP](#plain-lan-over-http)** — zero setup if you only want
  to open the app in a browser on your home Wi-Fi. The PWA features won't
  activate, and login travels in cleartext on your local network.

> **Why HTTPS matters here:** browsers only enable service workers, "Add to Home
> Screen" installs, and other PWA features in a *secure context*. `localhost` is
> exempt, but plain `http://` over the LAN is **not** — so to get the full app
> experience on a phone you need HTTPS, which the Tailscale path provides for free.

---

## Tailscale (recommended)

[Tailscale](https://tailscale.com) builds a small private network ("tailnet") of
*your* devices. Your server and your phone join it, and Tailscale provisions a
real, device-trusted TLS certificate on a `*.ts.net` hostname. Nothing is exposed
to the public internet, and it works on cellular/other Wi-Fi too — no
port-forwarding, no firewall changes.

You need Tailscale on **two** devices: the **server** (the machine running
Stellar Grooves) and the **client** (your phone), both signed into the **same
account**.

### 1. Install Tailscale on the server

macOS (Homebrew):

```bash
brew install tailscale
sudo brew services start tailscale     # run the daemon as a background service (survives reboots)
sudo tailscale up                      # opens a browser to sign in; wait for "Success."
```

Linux: see <https://tailscale.com/download/linux>, then `sudo tailscale up`.

Confirm it joined:

```bash
tailscale status     # your machine should be listed and online
```

Note your machine's name — it looks like `your-machine.tailXXXXXX.ts.net`.

### 2. Enable HTTPS certificates (one-time)

In the [admin console](https://login.tailscale.com/admin/dns) → **DNS**:

- Turn on **MagicDNS** (if not already on).
- Turn on **HTTPS Certificates**.

`tailscale serve` can't issue a TLS certificate without this.

### 3. Serve Stellar Grooves over HTTPS

Assuming the app listens on the default port `8089`:

```bash
sudo tailscale serve --bg 8089
tailscale serve status     # shows the public-within-your-tailnet HTTPS URL
```

This proxies `https://your-machine.tailXXXXXX.ts.net` → `http://127.0.0.1:8089`.
The first request takes a few seconds while the certificate is issued.

To undo it later: `sudo tailscale serve reset`.

### 4. Configure the app for the proxy (important)

Out of the box the app assumes it is reached directly at `http://localhost:8089`.
Behind the Tailscale HTTPS proxy you must tell it two things, or you'll hit the
errors in [Troubleshooting](#troubleshooting):

| Setting | Env var | Value | Why |
|---------|---------|-------|-----|
| Forwarded headers | `SERVER_FORWARD_HEADERS_STRATEGY` | `native` | So the app knows the edge is HTTPS and generates `https://` redirects (and recognizes requests as same-origin). |
| CORS allow-list | `STELLAR_GROOVES_CORS_ALLOWEDORIGINS` | include your `https://…ts.net` origin | So the browser's cross-origin requests from the `ts.net` hostname are accepted. |

> `SERVER_FORWARD_HEADERS_STRATEGY=native` uses Tomcat's `RemoteIpValve`, which
> only trusts forwarded headers from loopback/private proxy IPs — so a directly
> exposed instance can't be tricked by spoofed headers. The base
> `application.properties` defaults this to `native`; the env var lets you override
> it per deployment (e.g. `framework` for a proxy on a public address).

**Development run (`mvn spring-boot:run`)** — relaxed-binding env vars work in any
profile:

```bash
STELLAR_GROOVES_CORS_ALLOWEDORIGINS='http://localhost:8089,http://127.0.0.1:8089,https://your-machine.tailXXXXXX.ts.net' \
SERVER_FORWARD_HEADERS_STRATEGY=native \
mvn spring-boot:run
```

**Production run (`prod` profile)** — `CORS_ALLOWED_ORIGINS` is wired up under
`prod`, so:

```bash
CORS_ALLOWED_ORIGINS='https://your-machine.tailXXXXXX.ts.net' \
java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Restart the app after setting these.

### 5. Connect the phone

1. Install **Tailscale** from the App Store / Play Store and sign in with the
   **same account** used on the server.
2. Toggle Tailscale **on** (the switch should show connected).
3. Open your browser to `https://your-machine.tailXXXXXX.ts.net/login` and sign in.
4. Optional — install the PWA: browser **Share → Add to Home Screen**. It now
   installs as a standalone app with offline support and lock-screen controls.

> Going straight to `/login` avoids the bare-root redirect. If forwarded headers
> are configured correctly (step 4) the bare root works too.

### Keep the server reachable

- The server must stay awake. On a Mac mini acting as a server, disable sleep:
  System Settings → Energy (or `sudo pmset -a sleep 0`).
- `tailscale serve --bg` and the `brew services` daemon both persist across
  reboots; just remember to relaunch the app itself with the env vars from step 4.

---

## Plain LAN over HTTP

If you only want to open the app in a browser on the same Wi-Fi and don't need the
installable PWA, you don't have to install anything extra — the app already binds
to all interfaces (`server.address=0.0.0.0`).

1. Find the server's LAN address (macOS: `ipconfig getifaddr en0`) or use its
   `*.local` (Bonjour) name, e.g. `your-machine.local`.
2. From any device on the network: `http://your-machine.local:8089`
   (or `http://<lan-ip>:8089`).
3. Add the LAN origin to the CORS allow-list so login works, e.g.:
   ```bash
   STELLAR_GROOVES_CORS_ALLOWEDORIGINS='http://localhost:8089,http://your-machine.local:8089,http://<lan-ip>:8089' \
   mvn spring-boot:run
   ```
4. On first connection, allow the macOS firewall prompt for `java` if it appears.

Caveats: PWA install/offline won't work (no HTTPS), and credentials travel in
cleartext on your LAN. For anything beyond casual home use, prefer Tailscale.

---

## Behind any reverse proxy (Caddy, nginx, …)

The same two settings apply to any TLS-terminating reverse proxy, not just
Tailscale:

- `server.forward-headers-strategy=native` (or `framework` if your proxy is **not**
  on a loopback/private address) so the app emits correct external `https://` URLs.
- Add the external origin to the CORS allow-list (`CORS_ALLOWED_ORIGINS` under the
  `prod` profile).

Without forwarded-header handling, the app generates `http://internal-host:8089`
redirects, which break when the edge is HTTPS-only.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Browser shows a plain error "document" / **502 Bad Gateway** | The proxy is up but the app isn't listening on the target port. | Make sure Stellar Grooves is actually running on the port you passed to `tailscale serve` (default `8089`). |
| **The bare URL won't load**, but `/login` does | `/` redirects to `http://…:8089`, which an HTTPS-only proxy doesn't serve. | Set `SERVER_FORWARD_HEADERS_STRATEGY=native` and restart. |
| **"Invalid CORS request"** on sign-in | The `ts.net` (or proxy) hostname isn't in the CORS allow-list, and/or the app doesn't know it's behind HTTPS. | Add the `https://…ts.net` origin to `STELLAR_GROOVES_CORS_ALLOWEDORIGINS` **and** set `SERVER_FORWARD_HEADERS_STRATEGY=native`; restart. |
| Page **won't load at all** on the phone | Tailscale isn't active on the phone, or MagicDNS hiccup. | Confirm the Tailscale toggle is on and the server appears in the phone's device list. Try the raw tailnet IP (`http://100.x.y.z:8089/login`); if that works but the `.ts.net` name doesn't, toggle Tailscale off/on to refresh MagicDNS. |
| Unstyled "document" look | A 502 or error page (not the real app). | Almost always the 502 case above — verify the app is running. |

> The CORS origin and `ts.net` hostname are specific to your machine, so keep them
> in your launch script or an `.env` file rather than committing them to the repo.
