# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Prometheus metrics at `/actuator/prometheus`: health checks by outcome and duration, incidents opened and open, time
  to detect, time to recover by resolution (verified recovery or observed), recovery policy decisions, recovery
  attempts and duration.
- Optional `monitoring` Compose profile with Prometheus and a provisioned Grafana dashboard.
- OpenTelemetry tracing (exported when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set); trace ids and the incident id are
  carried into incident worker threads and log lines.
- `json-logs` profile for structured ECS log lines.
- Durable recovery action journal (`recovery_actions`, migration V12). Each attempt's intent is committed before the
  Docker call, then recorded as executed and verified. On startup RELAY resumes recoveries a stopped backend left
  running: it re-verifies instead of repeating the action, retries only within the attempt limit, and hands back an
  incident whose action never started. `GET /api/incidents/{id}/recovery-actions` lists the journal;
  `relay_recovery_resumed_total` counts resumes.

- Leader election for running several backend instances: a lease in PostgreSQL (`leader_leases`, migration V13),
  renewed on its own thread. Only the leader monitors, runs AI retries and runs recovery; other instances forward
  recovery and health-check requests to it. A new leader resumes the previous leader's recoveries from the action
  journal. `relay_leader` reports which instance leads. Compose `ha` profile adds a second backend.

### Security
- The backend no longer mounts the Docker socket. Recovery and inspection go through `docker-proxy`, which admits only
  container inspect, logs, stats and list, start, restart, network inspect and network connect; the patch sandbox goes
  through `sandbox-proxy`, limited to `relay-sandbox-*` containers with host bind mounts refused. Both answer only the
  backend service.

### Changed
- Recovery stops before its next action, without recording an outcome, if the instance loses leadership; the new
  leader resumes it.
- The per-incident attempt limit also counts journaled attempts, so a restart cannot reset it.
- The sandbox runs a build with `start` and `wait` and copies its output out of the container by name, instead of
  `start -a`, so every Docker call can be limited to the sandbox container's name.

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
