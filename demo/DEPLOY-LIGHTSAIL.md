# Deploying the public demo on AWS Lightsail

Goal: **https://demo.stellargrooves.com** serving the frozen demo, with automatic
HTTPS and a nightly reset — for ~**$12/month**, fixed.

How it works: one small Lightsail instance runs the whole stack (app + MongoDB +
Caddy) via Docker Compose. The app is a **prebuilt image pulled from GHCR**, so
the box never compiles anything. Caddy obtains a Let's Encrypt certificate
automatically. A nightly cron job wipes whatever visitors changed back to the
seed.

> You only do this once. Steps 0 and 3 happen in GitHub / your DNS provider;
> everything else is the Lightsail console + a few SSH commands.

---

## 0. Publish the demo image (GitHub, one time)

1. Merge the PR that adds this file (it also adds the manual workflow trigger and
   the prod overlay).
2. GitHub → **Actions** → **Release** → **Run workflow** (branch `main`). This
   builds and pushes `ghcr.io/jeffrey-stellar/stellar-grooves:latest`
   (multi-arch). Takes a few minutes.
   - When you later cut the real release, `git tag v0.1.0 && git push origin v0.1.0`
     publishes versioned tags too — either works.
3. Make the package public so the box can pull it without logging in:
   GitHub → your avatar → **Packages** → **stellar-grooves** → **Package
   settings** → **Change visibility** → **Public**.
   *(This publishes only the container image. Your source repo stays private.)*

---

## 1. Create the instance

Lightsail console → **Create instance**:

- **Region:** pick one near your users (e.g. `us-east-1`).
- **Platform:** Linux/Unix · **Blueprint:** *OS Only* → **Ubuntu 24.04 LTS**.
- **Launch script:** paste the entire contents of
  [`scripts/bootstrap-host.sh`](scripts/bootstrap-host.sh) — it installs Docker on
  first boot.
- **Plan:** the **$12/mo** tier (2 GB RAM, 2 vCPU, 60 GB SSD).
- **Name:** `stellar-grooves-demo` → **Create instance**.

---

## 2. Static IP + firewall

1. Lightsail → **Networking** → **Create static IP** → attach to the instance.
   Note the IP.
2. Instance → **Networking** → **IPv4 Firewall** → add/confirm:
   - **HTTP** TCP **80** (needed for the cert challenge)
   - **HTTPS** TCP **443**  ← add this one
   - **SSH** TCP **22** (present; optionally restrict to your IP)

---

## 3. DNS

At whatever manages DNS for `stellargrooves.com` (Cloudflare, Route 53,
registrar):

- Add an **A record**: `demo.stellargrooves.com` → **\<static IP\>**.
- If using **Cloudflare**, set this record to **DNS only** (grey cloud) so Caddy
  can complete the Let's Encrypt challenge directly.

Confirm it resolves before continuing:

```bash
dig +short demo.stellargrooves.com   # should print your static IP
```

---

## 4. Copy the demo files to the box

The box needs the `demo/` folder (compose files, seed, CC audio, scripts — 32 MB).
Grab your instance's SSH key from Lightsail → **Account** → **SSH keys** (download
the default key). From the **repo root** on your Mac:

```bash
ssh -i ~/Downloads/LightsailDefaultKey.pem ubuntu@<static-ip> 'mkdir -p ~/stellar-grooves-demo'
scp -i ~/Downloads/LightsailDefaultKey.pem -r demo ubuntu@<static-ip>:~/stellar-grooves-demo/
```

You now have `~/stellar-grooves-demo/demo/...` on the box.

---

## 5. Configure and launch

SSH in (`ssh -i ...Key.pem ubuntu@<static-ip>`, or the browser SSH button), then:

```bash
cd ~/stellar-grooves-demo
cp demo/.env.example demo/.env
nano demo/.env
```

Set these in `demo/.env`:

| Key | Value |
| --- | --- |
| `JWT_SECRET` | output of `openssl rand -base64 32` |
| `DEMO_DOMAIN` | `demo.stellargrooves.com` |
| `ACME_EMAIL` | a real address (Let's Encrypt expiry notices) |
| `CORS_ALLOWED_ORIGINS` | `https://demo.stellargrooves.com` |
| `SG_IMAGE` | leave default (`...:latest`) |

Pull the images and start the stack (app + Mongo + Caddy):

```bash
docker compose --env-file demo/.env \
  -f demo/docker-compose.demo.yml -f demo/docker-compose.image.yml \
  --profile tls pull

docker compose --env-file demo/.env \
  -f demo/docker-compose.demo.yml -f demo/docker-compose.image.yml \
  --profile tls up -d
```

> If you get a docker permission error, you were just added to the `docker` group
> — run `newgrp docker` (or log out/in) and retry.

Seed the database and warm the cover art:

```bash
./demo/scripts/reset-demo.sh
```

**Check it:** open <https://demo.stellargrooves.com>. You should land on the
pre-filled demo login (`demo` / `GrooveDemo1`). The very first load can take
~30 s while Caddy fetches the certificate.

---

## 6. Nightly reset (cron)

Keep it frozen — wipe visitor changes every night at 4am:

```bash
crontab -e
```

Add:

```cron
0 4 * * * cd /home/ubuntu/stellar-grooves-demo && ./demo/scripts/reset-demo.sh >> /home/ubuntu/sg-demo-reset.log 2>&1
```

---

## Updating the demo later

1. Re-run **Actions → Release → Run workflow** (or push a new tag) to publish a
   fresh image.
2. On the box:

```bash
cd ~/stellar-grooves-demo
docker compose --env-file demo/.env -f demo/docker-compose.demo.yml -f demo/docker-compose.image.yml --profile tls pull
docker compose --env-file demo/.env -f demo/docker-compose.demo.yml -f demo/docker-compose.image.yml --profile tls up -d
./demo/scripts/reset-demo.sh
```

If the `demo/` files themselves changed (compose, seed, scripts), re-run the
`scp` from step 4 first.

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Cert won't issue | Confirm 80 **and** 443 are open (step 2) and DNS resolves to the static IP (step 3). `docker compose ... logs caddy`. |
| `pull access denied` / `manifest unknown` | The GHCR package isn't Public yet (step 0.3), or `SG_IMAGE` tag is wrong. |
| App won't start | `docker compose ... logs app`. Check `JWT_SECRET` and `CORS_ALLOWED_ORIGINS` are set in `demo/.env`. |
| Covers missing | Re-run `./demo/scripts/reset-demo.sh`; ~18 of 21 albums resolve from public providers. |

## Cost

~$12/mo (Lightsail 2 GB) + static IP (free while attached) + negligible egress.
No surprise metered charges on the flat-rate plan.
