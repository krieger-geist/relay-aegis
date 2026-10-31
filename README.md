# RELAY AEGIS

[![CI](https://github.com/krieger-geist/relay-aegis/actions/workflows/ci.yml/badge.svg)](https://github.com/krieger-geist/relay-aegis/actions/workflows/ci.yml)
[![CodeQL](https://github.com/krieger-geist/relay-aegis/actions/workflows/codeql.yml/badge.svg)](https://github.com/krieger-geist/relay-aegis/actions/workflows/codeql.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Evidence-driven self-healing for software you operate.**
RELAY AEGIS detects failures, explains them from evidence, repairs what it can repair safely, and proves the result before it calls anything fixed.

```
DETECT → COLLECT EVIDENCE → DIAGNOSE → RANK → CLASSIFY → REPAIR / RECOMMEND → VERIFY → RESULT
```

Nothing is marked *resolved* because an action ran. A runtime repair is resolved only after health verification passes; a code repair ends as a reviewed pull request that a human merges.

---

## Three boundaries, one engine

RELAY works within three evidence boundaries. They are separate, not ranked: each one decides what RELAY may observe and what it may change.

| Boundary | In the app | Evidence | Action |
|---|---|---|---|
| **Runtime self-healing** | Runtime → Systems, Incidents, Recovery history | Health, containers, logs, dependencies | Allowlisted, policy-checked runtime recovery, then verification. No source changes. |
| **Code self-healing** | Code → Repositories (`/codebase`), Repairs | Repository analysis | Repository → Findings → Diagnosis → Repair → Sandbox Validation → Security Re-scan → Human Approval → Branch → Commit → Pull Request → Human Merge |
| **Live URL investigation** | Live URL → Investigations (`/live-url`): *Live URL* or *Live URL + Repo* | URL → DNS → TCP → TLS → HTTP → Evidence → Diagnosis | A structured report; never a code change. A repository adds evidence; a relationship is "possibly related", never "caused by". |

Code and Live URL are separate pages on one assessment engine: Repositories never shows live URL controls, and Investigations never shows Auto-Repair, Apply or any GitHub repair control. The interface names the boundaries (Runtime, Code, Live URL) rather than numbering them. The Overview shows one card per boundary, built only from recorded runs; incident notifications (attention strip, bell, sidebar badge) are acknowledged per account and never change the incident.

## What it does

| Area | What actually happens |
|---|---|
| **Runtime self-healing** | Monitors registered applications, opens incidents after consecutive failed health checks, inspects the allowlisted Docker container, and diagnoses with a deterministic pattern engine (container exited, crash loop, OOM, connection refused, timeout, HTTP 5xx, dependency down, …). Recovery actions are a closed set (`START_CONTAINER`, `RESTART_CONTAINER`, `RESTART_DEPENDENCY`, `RECONNECT_NETWORK` — reattach a healthy dependency to its own Compose network under its service alias); everything else is `INVESTIGATE` or `ESCALATE`. Every action passes the recovery policy, a guard (attempt limit, cooldown, circuit breaker) and post-action health verification. |
| **Runtime → Code** | When a dependency rejects an application's credentials, Runtime offers no restart (it would reuse the same credential) and links the incident to the hard-coded credential that Code found in the system's repository. The fix runs through Code's normal sandbox, approval and pull-request flow; the incident closes as fixed by that PR only after it is merged and the system is healthy again. |
| **Live URL diagnosis** | Bounded GETs to an allowlisted host. Localizes the fault layer by layer (DNS → TCP → TLS → HTTP; a layer after a failure is *not tested*), samples repeatedly (persistent / intermittent / recovered / stable), records latency, a second endpoint, the monitoring baseline, content type and security headers, and states what cannot be determined from outside. **What changed:** each run is compared with the previous run of the same URL (layers, status, redirect, certificate, security headers, a large latency shift), showing only differences between two recorded runs. **Certificate evidence:** for HTTPS, the certificate presented on the investigation's own TLS connection (subject, SANs, issuer, validity, days remaining, chain, TLS version and cipher), with host-name match and chain trust reported separately, even when the handshake is rejected; plain HTTP is *not tested*. **Where the time goes:** connection setup (DNS, TCP, TLS) and each request (first byte, download) as a timing breakdown. Black-box only: nothing is modified and response bodies are never stored. |
| **GitHub analysis** | Read-only shallow clone of a public repository; static rules for secrets, container hardening, injection patterns, configuration risks and maintainability. Repository code is never executed during analysis. |
| **Auto-Repair (code)** | For findings with a deterministic strategy (`credential-literal-to-env`, `dockerfile-non-root-user`): safety checks → patch generated against the assessed commit → applied in a **disposable Docker sandbox** → build → tests (with a baseline run on the unpatched commit when tests fail) → security re-scan (targeted finding must go 1 → 0 with no new finding). Only a validated patch can be applied. |
| **Approval & pull request** | An authorized approver applies the reviewed patch: RELAY creates `relay-aegis/fix/<finding>`, commits exactly the reviewed change, opens a pull request against the default branch and verifies the branch. It never pushes to the default branch, never force-pushes and never merges. Repeating the request reuses the same PR. |
| **Merge & resolution** | RELAY never merges. It reads the PR's merge status from GitHub; a repair is *Resolved* only after a human merge **and** a re-scan of the default branch shows the finding gone. A failed build is attributed by building the unpatched commit too. An exact commit can be assessed with `https://github.com/owner/repo/commit/<40-char SHA>`. |
| **Combined assessment** | Correlates live URL, repository and runtime evidence without inventing causation; unrelated findings are reported as independent. |
| **AI (optional)** | Gemini can add an explicitly labelled *hypothesis*. AI never executes actions, never authorizes recovery and never marks anything resolved. Without a key, the deterministic engine works unchanged. |

### Unified findings
Every finding — from any source — uses one model: severity, confidence, evidence statements tagged **Observed / Inferred / Hypothesis / Cannot determine**, evidence sufficiency, impact, recommendation, repairability and verification state. Findings are ranked and grouped as **Auto-repairable**, **Action required** or **Informational**.

### Repair states
`Repairable` → `Repairing` → `Awaiting approval` → `Repair attempted` / `Resolved`, or `Validation failed`, `Blocked`, `Verification failed`, `Manual action required`. Patch generated, validated, applied and verified are always separate steps.

---

## Safety model

- **Authentication:** JWT access tokens (in memory) + rotating HttpOnly refresh cookie; BCrypt password hashing; login attempt limiting; roles `USER` / `ADMIN`.
- **Allowlists:** monitoring hosts, assessment hosts, recoverable Docker containers and remediation repositories are explicit environment allowlists.
- **Production protection:** recovery against applications marked Production is blocked by policy. Enabling automatic recovery requires typing `CONFIRM-AUTO-REPAIR`.
- **High availability:** run several backend instances (`docker compose --profile ha up -d` adds a second one). They elect one leader through a lease row in PostgreSQL; only the leader monitors and runs recovery, and the others forward recovery and health-check requests to it. A leader stops acting before its lease can be taken over, so two never act at once; if it dies, another instance takes over within about 20 s and resumes its recoveries from the journal. Instances need a shared `RELAY_AUTH_JWT_SECRET`.
- **Durable action journal:** every recovery attempt's intent is committed to PostgreSQL before Docker is called, then marked executed and verified. If the backend stops mid-recovery, it resumes on startup by verifying the current state: an action whose result is unknown is never repeated, further attempts stay within the attempt limit, and an incident whose action never started is handed back for review. `GET /api/incidents/{id}/recovery-actions` shows the journal.
- **Least-privilege Docker access:** the backend never mounts the Docker socket. It reaches Docker through two socket proxies that answer only the `backend` service and admit only listed API calls (everything else gets HTTP 403). `docker-proxy` (recovery and inspection) allows reading container state, logs, stats and networks, starting or restarting a container and attaching it to a network; no create, exec, delete, image or volume calls. `sandbox-proxy` allows creating, copying into and out of, starting, waiting for and removing only `relay-sandbox-<12 hex>` containers, and pulling build images; host bind mounts are refused.
- **Sandbox:** throwaway container per run, all Linux capabilities dropped, `no-new-privileges`, memory/CPU/PID limits, removed afterwards; network `none` unless configured.
- **Approvals:** pull requests can be created only by ADMIN users or emails in `RELAY_GITHUB_REMEDIATION_APPROVERS`, only for repositories in `RELAY_GITHUB_REMEDIATION_REPOSITORIES`, and only for validated patches.
- **Secrets:** API keys and tokens stay in backend environment variables; they are never returned by the API, logged or sent to the browser. Credential literals are redacted in findings and patch previews.
- **Audit:** recovery policy decisions, attempts and verifications are recorded as incident evidence; every GitHub remediation attempt is recorded with the approving user, branch, commit and verification result.

---

## Architecture

```
React 18 + Vite + TypeScript  ──/api──▶  nginx  ──▶  Spring Boot 3.4 (Java 21)
                                                     ├─ diagnostics/   deterministic pattern engine
                                                     ├─ recovery/      policy · guard · executor · verification
                                                     ├─ assessment/    GitHub + live URL analyzers
                                                     ├─ findings/      canonical findings · ranking · correlation
                                                     ├─ remediation/   patch strategies · sandbox · GitHub PR flow
                                                     ├─ repair/        unified Auto-Repair orchestration
                                                     └─ auth/          JWT · refresh tokens · roles
                                                          │
                                   PostgreSQL 16 (Flyway) ┘   Docker Engine (allowlisted containers, sandbox)
```

`test-target/` is a tiny Node health endpoint used as the allowlisted, non-production container for recovery demonstrations.

---

## Run it

Requirements: Docker Desktop (Compose v2). For development outside Docker: JDK 21, Maven 3.9+, Node 20.

```powershell
# create .env only if you do not have one yet
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --build
```

- UI: http://localhost:5173 (register an account; emails in `RELAY_AUTH_ADMIN_EMAILS` become ADMIN)
- API health: http://localhost:8080/api/health
- Test target: http://localhost:8091/health

Key settings in `.env` (see `.env.example` for all of them):

| Variable | Purpose |
|---|---|
| `RELAY_MONITORING_ALLOWED_HOSTS`, `RELAY_ASSESSMENT_ALLOWED_HOSTS` | Hosts that may be monitored / assessed |
| `RELAY_ASSESSMENT_ALLOW_PUBLIC_HOSTS` | `true` lets Live URL investigate any public website (off by default). Private and internal addresses stay blocked and are re-checked before every request; standard ports only; no redirects, credentials or query strings |
| `RELAY_DOCKER_ALLOWED_CONTAINERS` | Containers recovery may act on (default `relay-aegis-test-target`) |
| `RELAY_SANDBOX_ENABLED`, `RELAY_SANDBOX_NETWORK` | Enable sandbox validation; `bridge` when builds must download dependencies |
| `RELAY_GITHUB_TOKEN`, `RELAY_GITHUB_REMEDIATION_REPOSITORIES`, `RELAY_GITHUB_REMEDIATION_APPROVERS` | Pull-request remediation (fine-grained token, contents + pull requests write on the listed repositories only) |
| `RELAY_AUTH_JWT_SECRET` | Token signing secret (set one for anything beyond a throwaway run) |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Optional AI hypotheses |
| `RELAY_LEADER_ELECTION_ENABLED`, `RELAY_LEADER_LEASE_TTL_MS`, `RELAY_LEADER_RENEW_INTERVAL_MS`, `RELAY_LEADER_ADVERTISE_URL` | Leader election between backend instances (on in the `postgres` profile; lease 15 s, renewed every 5 s, at most a third of the lease). The advertise URL is where the other instances reach this one; blank: `http://<container address>:8080`. |
| `RELAY_DOCKER_HOST`, `RELAY_SANDBOX_DOCKER_HOST` | Docker endpoints for recovery and for the sandbox (Compose: the two socket proxies). Blank: the Docker CLI's default socket, for development outside Docker |
| `RELAY_SPRING_PROFILES` | `postgres` (default); `postgres,json-logs` for one JSON (ECS) object per log line |
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin password for the monitoring profile |

### Observe RELAY itself

RELAY exports its own operating metrics in Prometheus format at `http://localhost:8080/actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `relay_health_checks_total{application, outcome}` | Health checks recorded, by outcome |
| `relay_health_check_duration_seconds` | Time taken by each check |
| `relay_incidents_opened_total`, `relay_incidents_open` | Incidents opened; incidents not yet recovered |
| `relay_incident_detection_seconds` | Time to detect: first failed check → incident opened |
| `relay_incidents_resolved_total{resolution}` | `verified_recovery` (a RELAY action passed verification) or `observed` (seen healthy, no verified action) |
| `relay_incident_recovery_seconds{resolution}` | Time to recover: first failed check → recovery |
| `relay_recovery_decisions_total{action, decision, mode}` | Policy decisions: `authorized`, `requires_approval`, `blocked` |
| `relay_recovery_attempts_total{action, verification}`, `relay_recovery_duration_seconds` | Executed attempts and their verification; authorization → final verification |

Values are recorded when the matching record is stored, so the metrics never claim more than the incident records.

```powershell
docker compose --profile monitoring up -d   # adds Prometheus (localhost:9090) and Grafana (localhost:3300)
```

Grafana opens on the **RELAY AEGIS** dashboard (`monitoring/grafana/dashboards/relay-aegis.json`): open incidents,
mean time to detect and recover, check outcomes and latency, detection/recovery percentiles, how incidents were
resolved, policy decisions and recovery attempts.

Trace and span ids are added to every log line, and the incident id to every line written while an incident is
diagnosed or recovered. To export traces, set `MANAGEMENT_OTLP_TRACING_ENDPOINT` on the backend (for example
`http://otel-collector:4318/v1/traces`).

---

## Chaos script: break it yourself

[`chaos/chaos.ps1`](chaos/chaos.ps1) injects each failure on demand into the protected application, Smart Business AI
Assistant (`sba-frontend` → `sba-backend` → `sba-postgres`), and measures what happens. Every injection is reversible
with `reset`; real data is never changed.

```powershell
cd chaos
.\chaos.ps1 list                                  # the failures
.\chaos.ps1 status                                # containers and health endpoints
.\chaos.ps1 stop-db                               # inject one failure
.\chaos.ps1 measure stop-db -Healing on           # inject, time it, record it
.\chaos.ps1 reset                                 # put everything back
.\chaos.ps1 ready                                 # wait until it is safe to inject the next failure
```

| Failure | What it does |
|---|---|
| `stop-backend` | Stops the backend container |
| `crash-backend` | Makes the backend process exit; Docker's restart policy brings it back |
| `freeze-backend` | Freezes the backend process (SIGSTOP); the container keeps running |
| `stop-db` | Stops PostgreSQL |
| `block-db` | Disconnects PostgreSQL from its network; both containers stay up |
| `corrupt-record` | Inserts one order whose status the app cannot read; the Orders page fails while health stays green |
| `flood` | 20 000 API requests, 300 at a time |
| `rotate-password` | Rotates the orders-service database password while the service keeps a hard-coded one |

`measure` polls the application's own health endpoint every second, independently of RELAY, and records when it went
down and when it came back. It then reads RELAY's incident record for the same failure (time to detect, time to
recover) and appends a row to `chaos/results/chaos-results.csv`. `-Healing on|off` labels the run: *on* when the
recovery policies are set to **Automatic** in Recovery policies, *off* when they are not.

Measured results, healing off vs on, are in [chaos/RESULTS.md](chaos/RESULTS.md); the full write-up (architecture,
failure table, before-and-after numbers, known limits) is the
[Resilience Report](docs/RELAY-AEGIS-Resilience-Report.pdf).

AI use is declared in the [AI Use Declaration](RELAY_AEGIS_AI_Use_Declaration.pdf).

---

## Tests

```powershell
cd backend;  mvn -B verify
cd frontend; npm ci; npm run lint; npm run typecheck; npm run test; npm run build
docker compose config -q
```

CI runs the same checks on every push and pull request, plus the PostgreSQL integration tests (Testcontainers; skipped
locally when Docker is not running), Docker image builds with a Trivy scan, and CodeQL. Tagging `vX.Y.Z` publishes signed
images to GitHub Container Registry. Live-run results are recorded in [PROJECT_STATUS.md](PROJECT_STATUS.md). The HTTP API is described in [openapi.yaml](openapi.yaml).

---

## Scope and limitations

- Local, single-host deployment, not hardened for exposure to untrusted networks. The socket proxies hold the Docker socket; the sandbox proxy can create containers and does not inspect the creation request beyond bind mounts (a `--privileged` request would pass), so keep `RELAY_SANDBOX_ENABLED=false` unless you need sandbox validation.
- Repository analysis is heuristic static scanning of public repositories; findings are candidates until validated.
- Two deterministic code-repair strategies exist; other findings receive recommendations, not patches.
- Removing a hard-coded credential does not revoke it — rotate it with its issuer.

---

## Contributing, security and licence

See [CONTRIBUTING.md](CONTRIBUTING.md), report vulnerabilities as described in [SECURITY.md](SECURITY.md), and find
changes in [CHANGELOG.md](CHANGELOG.md) and planned work in [docs/ROADMAP.md](docs/ROADMAP.md).
Licensed under the [Apache License 2.0](LICENSE).
