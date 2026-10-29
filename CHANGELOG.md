# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-29

First public release: the Sentry × Dezyne E'cole Hackathon 2026 system, with release engineering added.

### Added
- **Runtime self-healing:** monitoring, incidents after consecutive failed checks, deterministic diagnosis with ranked
  hypotheses, and policy-gated recovery (`START_CONTAINER`, `RESTART_CONTAINER`, `RESTART_DEPENDENCY`,
  `RECONNECT_NETWORK`) with attempt limits, cooldown, circuit breaker and post-action health verification.
- **Runtime → Code:** rejected dependency credentials are linked to the hard-coded credential in the system's
  repository and fixed through a reviewed pull request instead of a restart.
- **Code self-healing:** read-only repository analysis, deterministic patch strategies (`credential-literal-to-env`,
  `dockerfile-non-root-user`), disposable sandbox build, tests and security re-scan, approval-gated pull requests.
- **Live URL investigation:** DNS → TCP → TLS → HTTP localization, sampling, certificate evidence, timing breakdown
  and comparison with the previous run.
- Unified findings model, per-account notification acknowledgement, optional Gemini hypotheses.
- Chaos script (`chaos/chaos.ps1`) with measured results and the Resilience Report.
- Continuous integration: backend build and tests, PostgreSQL integration tests (Testcontainers), frontend lint,
  type check, tests and build, Docker image build with Trivy scanning.
- CodeQL analysis, Dependabot updates, and a tag-triggered release publishing signed images to GitHub Container Registry.
- Apache-2.0 licence, security policy, contribution guide, issue and pull request templates.

### Changed
- Frontend image installs dependencies with `npm ci` from the lockfile for reproducible builds.

[Unreleased]: https://github.com/krieger-geist/relay-aegis/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/krieger-geist/relay-aegis/releases/tag/v1.0.0
