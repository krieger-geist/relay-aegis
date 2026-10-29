# Contributing to RELAY AEGIS

Thanks for helping. RELAY acts on running systems and repositories, so changes are held to two standards: they must be
tested, and they must keep RELAY truthful about what it observed and what it did.

## Before you start

- Read [PROJECT_RULES.md](PROJECT_RULES.md). In short: never invent data or outcomes, keep AI advisory, keep every
  action allowlisted and policy-gated, and call something fixed only after verification passes.
- For anything larger than a small fix, open an issue first so the approach can be agreed.
- Report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).

## Local setup

Requirements: JDK 21, Maven 3.9+, Node 22, Docker (Compose v2).

```bash
cp .env.example .env          # only if you do not have one yet
docker compose up -d --build  # full stack: UI on http://localhost:5173, API on http://localhost:8080
```

Backend and frontend can also run outside Docker; see the README.

## Checks (the same ones CI runs)

```bash
cd backend && mvn -B verify
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
docker compose config -q
```

`mvn verify` includes PostgreSQL integration tests through Testcontainers. They run when Docker is available and are
skipped otherwise; CI always runs them.

## Making a change

1. Branch from `main` (`feature/…`, `fix/…`, `docs/…`).
2. Keep the change focused, and add or update tests with it.
3. Database changes are a new Flyway migration (`backend/src/main/resources/db/migration/V<n>__<name>.sql`); never edit
   a migration that has been released.
4. Frontend: use the CSS tokens and fonts named in the project rules, and check the page at 375 px width.
5. Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for anything a user would notice.
6. Open a pull request and fill in the template. CI must pass before review.

## Commit messages

Short imperative summary (≤ 72 characters), then a blank line and the why, if it is not obvious.

## Licence

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
