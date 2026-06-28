# Security Policy

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Report vulnerabilities through GitHub's private vulnerability reporting:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Fill in the form. The maintainer will be notified privately.

If GitHub private reporting is not available to you, email
**security@stellargrooves.com** instead.

## What to include

- A description of the vulnerability and its impact.
- Steps to reproduce, ideally with a minimal proof of concept.
- The version (or commit SHA) you tested against.
- Any suggested mitigation, if you have one.

## Response expectations

This is a small open source project maintained in spare time. You can expect:

- An acknowledgement within 7 days.
- A first assessment (severity, plan) within 14 days.
- A fix or written explanation of why a fix isn't possible within 60 days for
  high-severity issues. Lower-severity issues may take longer.

Please give the maintainer a reasonable window to fix the issue before public
disclosure. Coordinated disclosure is appreciated.

## Scope

In scope:

- The Stellar Grooves application code in this repository.
- Default configuration shipped in `application.properties` and `.env.example`.
- The Docker image published from this repository.

Out of scope:

- Vulnerabilities in third-party dependencies that are already publicly
  disclosed and have an upstream fix — please report those upstream.
- Issues that require physical access to the host running Stellar Grooves.
- Self-inflicted misconfigurations (e.g. running with the default `JWT_SECRET`
  unset, or exposing the app to the public internet without a reverse proxy).
