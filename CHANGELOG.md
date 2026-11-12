# Changelog

All notable changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.3.1] - 2026-11-13

Hardening for public instances, where anyone can sign up (such as the AWS demo).

### Added
- Run RELAY on one AWS server: `deploy/aws` (setup script, Compose override, Caddy with automatic HTTPS) and the
  [AWS guide](docs/AWS.md).
- Per-account hourly limits on starting assessments (default 30) and adding applications (default 20), answered with
  HTTP 429 and `Retry-After`. ADMIN accounts are not limited. Set with `RELAY_LIMIT_ASSESSMENTS_PER_HOUR` and
  `RELAY_LIMIT_APPLICATIONS_PER_HOUR`; the AWS deploy uses 10 and 3.

### Changed
- Only ADMIN accounts can change recovery policies and reload runbooks (HTTP 403 for others). Policies apply to every
  user, so before this any signed-in account could switch healing off for everyone. Settings shows the policies
  read-only to other accounts.

## [1.3.0] - 2026-11-07

Kubernetes release (roadmap Phase 3). RELAY now runs in a cluster from a Helm chart and repairs Deployments with
namespace-scoped permissions.

Verified on a kind cluster (Kubernetes v1.34.0): five real failures healed and verified, each in under 47 s, with fast
test timings. Rollback of a bad rollout took 14.3 s and 31.6 s in that run, with no outage seen from outside.
[Results and caveats](docs/KUBERNETES.md#measured-results).

### Added
- **Kubernetes targets.** An application's target can be `k8s:<namespace>/<deployment>`. Diagnosis, verification and the
  dependency graph read it through the same interface as Docker.
- **New actions:** `ROLLOUT_UNDO`, `SCALE_WORKLOAD` and `DELETE_POD`, with their own policies, all starting at
  `require_approval`. On Kubernetes, the restart actions are rollout restarts. Only Deployments listed in
  `kubernetes.allowedWorkloads` can be changed.
- **Kubernetes findings:** scaled to zero, image pull failure, bad rollout, unschedulable, one pod unready and all pods
  unready.
- **Kubernetes signals.** The leader polls watched Deployments, and failures such as `CrashLoopBackOff` and `OOMKilled`
  open an incident and hold it open even while the health URL answers.
- **Helm chart** in `deploy/helm/relay-aegis`: namespaced Role only, no ClusterRole, non-root containers.
- **`scripts/k8s-e2e.sh`** and a CI workflow that break a real Deployment on kind five ways and measure recovery.
- [Kubernetes guide](docs/KUBERNETES.md).
- `scripts/live-demo.ps1` shares a running RELAY through a temporary Cloudflare quick tunnel.

### Changed
- A passing health check no longer clears a Kubernetes workload whose pods are failing: old pods can keep answering
  during a bad rollout. Verification on Kubernetes waits for the latest rollout to complete.

### Not included
- Cordon, which was on the roadmap, because it needs cluster-wide permissions.
- A cluster test of dependency failure, because the test application has no database. Unit tests cover it.

## [1.2.0] - 2026-11-04

Pluggable core release (roadmap Phase 2). New failure types are YAML files, not Java changes; alerts from the tools a
team already runs open incidents; and RELAY reports to Slack, where approvers can approve or reject a recovery.

Verified live on Compose: a `connection-pool-exhausted` runbook, added as a file, matched its failure at confidence 0.96
and healed it (detected in 5.2 s, recovered in 10.7 s); an Alertmanager alert held its incident open through 19 passing
health checks and the incident closed 7 s after the alert resolved.

### Added
- **Runbooks as code.** `*.yaml` files in `runbooks/` (`RELAY_RUNBOOKS_DIR`) describe what must match (container
  state, log patterns, failed health checks, an unhealthy dependency), the cause and one action from the closed set.
  They are validated on load and re-read every minute; `GET /api/runbooks` shows what loaded and why a file was
  rejected, `POST /api/runbooks/reload` reloads at once. Each finding names the runbook file and its SHA-256. Two
  examples ship: `connection-pool-exhausted` and `disk-full`.
- **Alerts from other tools.** `POST /api/signals/alertmanager` (also Grafana's webhook contact point) and
  `/api/signals/webhook` with a bearer token (`RELAY_SIGNALS_TOKEN`), and `/api/signals/sentry` verified by the
  integration's HMAC signature (`RELAY_SENTRY_CLIENT_SECRET`, project mapping in `RELAY_SENTRY_PROJECTS`). A firing
  alert opens an incident or is added to the open one; repeats are recorded once, a still-firing alert at most hourly.
  `relay_signals_received_total` counts them.
- **Slack.** Messages when an incident opens, when recovery needs approval and when it is verified or not
  (`RELAY_SLACK_WEBHOOK_URL`). With `RELAY_SLACK_SIGNING_SECRET` and `RELAY_SLACK_APPROVERS`, approval messages get
  Approve / Reject buttons, handled at `POST /api/slack/interactions`.
- **Outbound webhook** (`RELAY_NOTIFY_WEBHOOK_URL`) with every notification as JSON, signed with
  `X-Relay-Signature: sha256=HMAC(secret, "<timestamp>.<body>")` when `RELAY_NOTIFY_WEBHOOK_SECRET` is set.
- `RELAY_PUBLIC_URL` for incident links in messages.
- [Integrations guide](docs/INTEGRATIONS.md) for Alertmanager, Grafana, Sentry, Slack and the webhook.

### Changed
- An incident opened or extended by an external alert stays open while that alert is still firing, even when RELAY's
  own checks pass; an alert that never resolves stops holding it after 6 hours.

### Fixed
- Concurrent writers (the monitor, diagnosis, recovery and incoming alerts) could overwrite each other's incident
  evidence. Evidence is now merged by id under a row lock.

### Security
- Slack clicks are refused unless signed with the app's signing secret over the raw body, less than five minutes old,
  and from a listed approver. An approval runs the normal operator recovery, so policy, guard, journal and verification
  still apply; Production incidents never get buttons, and alert text is escaped so it cannot add mentions or links.
- Signal endpoints compare credentials in constant time, cap request size, and are off until their credential is set.
- Outbound notifications never follow redirects; the Slack URL must be on `hooks.slack.com`.

## [1.1.0] - 2026-10-31

Reliability and observability release (roadmap Phase 1). RELAY can now run as several instances with exactly one
acting, survives being killed in the middle of a repair without losing the incident or repeating the action, reaches
Docker only through allowlisting proxies, and exposes Prometheus metrics, traces and structured logs.

Chaos-tested with two replicas: the leader was killed right after recording its intent to start a container; the other replica took
over in 17.5 s, verified instead of repeating the action, and closed the incident with exactly one container start.

### Added
- **High availability.** Leader election through a lease in PostgreSQL (`leader_leases`, migration V13), renewed on
  its own thread. Only the leader monitors, retries AI diagnosis and runs recovery; other instances forward recovery
  and health-check requests to it, and a new leader resumes the previous leader's recoveries. The Compose `ha` profile
  adds a second backend; `relay_leader` shows which instance leads.
- **Crash-safe recovery.** A durable action journal (`recovery_actions`, migration V12): each attempt's intent is
  committed before the Docker call, then recorded as executed and verified. After a crash or failover, RELAY verifies
  instead of repeating an action whose result is unknown, retries only within the attempt limit, and hands back an
  incident whose action never started. `GET /api/incidents/{id}/recovery-actions` lists the journal;
  `relay_recovery_resumed_total` counts resumes.
- **Metrics** at `/actuator/prometheus`: health checks by outcome and duration, incidents opened and open, time to
  detect, time to recover by resolution, recovery policy decisions, recovery attempts and duration.
- Optional `monitoring` Compose profile with Prometheus and a provisioned Grafana dashboard.
- OpenTelemetry tracing (exported when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set); trace ids and the incident id are
  carried into incident worker threads and log lines.
- `json-logs` profile for structured ECS log lines.

### Security
- **Least-privilege Docker access.** The backend no longer mounts the Docker socket. Recovery and inspection go through
  `docker-proxy`, which admits only container inspect, logs, stats and list, start, restart, network inspect and
  network connect; the patch sandbox goes through `sandbox-proxy`, limited to `relay-sandbox-*` containers with host
  bind mounts refused. Both answer only the backend service.

### Changed
- Recovery stops before its next action, without recording an outcome, if the instance loses leadership; the new
  leader resumes it.
- The per-incident attempt limit also counts journaled attempts, so a restart cannot reset it.
- The sandbox runs a build with `start` and `wait` and copies its output out of the container by name, instead of
  `start -a`, so every Docker call can be limited to the sandbox container's name.

### Upgrading
- Migrations V12 and V13 apply automatically on startup.
- Running more than one backend instance requires a shared `RELAY_AUTH_JWT_SECRET`.
- Compose now starts `docker-proxy` and `sandbox-proxy`; a custom deployment that mounted the Docker socket into the
  backend should point `RELAY_DOCKER_HOST` and `RELAY_SANDBOX_DOCKER_HOST` at equivalent proxies instead.

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

[Unreleased]: https://github.com/krieger-geist/relay-aegis/compare/v1.3.1...HEAD
[1.3.1]: https://github.com/krieger-geist/relay-aegis/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/krieger-geist/relay-aegis/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/krieger-geist/relay-aegis/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/krieger-geist/relay-aegis/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/krieger-geist/relay-aegis/releases/tag/v1.0.0
