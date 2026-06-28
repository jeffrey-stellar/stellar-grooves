# Contributing to Stellar Grooves

Thanks for your interest in contributing. Stellar Grooves is a self-hosted music
library for curators — bug reports, fixes, and well-scoped features are all
welcome.

## Getting set up

Prerequisites:

- Java 17 (Temurin recommended)
- Maven 3.9+
- Node.js 22+ (for frontend tests and vendor asset copy)
- Docker (for integration tests — Testcontainers needs a running daemon)
- A local MongoDB instance, or use the bundled `docker-compose.yml`

First-time setup:

```bash
git clone https://github.com/jeffrey-stellar/stellar-grooves.git
cd stellar-grooves
cp .env.example .env          # then fill in JWT_SECRET and MONGO_URI
npm install                   # also runs `copy-vendor` to populate static assets
docker compose up -d mongo    # or point MONGO_URI at your own instance
mvn spring-boot:run
```

The app starts on `http://localhost:8089`. See `HOW_TO_USE.md` for what to do
next.

## Running tests

```bash
mvn verify        # backend: unit tests + Testcontainers integration tests + JaCoCo (60% gate)
npm test          # frontend: Vitest
```

CI runs both on every pull request. The JaCoCo line-coverage gate is enforced
at 60% — PRs that drop coverage below that will fail.

## Branch and PR conventions

- Branch off `main` with a short descriptive name (`fix-rediscovery-n-plus-one`,
  `feature-smart-playlist-or`, `docs-readme-cleanup`).
- One concern per PR. If you find yourself writing "and also..." in the
  description, split it.
- Keep the diff focused: no drive-by reformatting in feature PRs (Spotless
  handles formatting).
- Reference any related issue in the PR description.

Commits don't need to follow Conventional Commits, but a clear imperative
subject line ("fix decade rollup for years before 1900") helps.

## Code style

- Java: `mvn spotless:apply` formats with Google Java Format. CI does not auto-
  format; format locally before pushing.
- JavaScript: no formatter is enforced — match the surrounding file.
- Don't add comments that restate the code. Comments should explain *why*, not
  *what*.
  
  ### Extending the artist catalog

One of the easiest ways for new contributors to get started is by adding or correcting 
artist-to-genre mappings in the catalog used for automatic genre classification.

Artist-to-genre mappings are stored in:

```text
src/main/resources/catalog.json
```

Each artist maps to one or more genre values:

```json
{
  "Metallica": ["THRASH_METAL"],
  "Guns N' Roses": ["HARD_ROCK", "HAIR_METAL"]
}
```

Valid genre values are:

* `CLASSIC_ROCK`
* `HARD_ROCK`
* `HAIR_METAL`
* `HEAVY_METAL`
* `THRASH_METAL`
* `OTHER`

If an artist belongs to multiple genres, place the primary genre first in the array.

Artist names are matched case-insensitively, so avoid creating duplicate entries that differ only by capitalization.

Before submitting a pull request, verify that your changes do not break existing functionality:

```bash
mvn test
npm test
```

  

## Reporting bugs

Open an issue with:

1. What you did (steps to reproduce).
2. What you expected.
3. What actually happened (logs / screenshots if relevant).
4. Your environment: app version (or commit), Java version, MongoDB version,
   browser if it's a UI bug.

## Proposing features

For anything larger than a small fix, open an issue first to discuss scope.
Stellar Grooves is positioned as a curator's library — features that don't
serve that audience (social feeds, external streaming integrations, file
upload UIs) are likely out of scope. See `README.md` for the project's stance.

## Security issues

Don't file public issues for security vulnerabilities. See `SECURITY.md`.

## Contributor License Agreement (CLA)

Before your first pull request can be merged, you'll need to sign the project's
Contributor License Agreement. The full text is in `CLA.md`. The CLA Assistant
bot will comment on your PR with a one-click sign-off — there's no separate
form, no email exchange, just a comment on your PR.

The CLA is short and standard: you confirm your contribution is your own work,
you grant the project a license to use it, and you allow the maintainer to
relicense the project in the future if needed (this is what lets a small OSS
project preserve commercialization options without coming back to every
contributor for permission).

You only need to sign once — the bot remembers, and future PRs are
automatically cleared.

## License

By contributing, you agree that your contributions will be licensed under the
GNU Affero General Public License v3.0 (the project's license — see `LICENSE`)
and you accept the terms of the Contributor License Agreement (`CLA.md`).
