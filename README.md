# RELAY AEGIS

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

---

## Tests

```powershell
cd backend;  mvn -B clean test
cd frontend; npm ci; npm run lint; npm run typecheck; npm run test; npm run build
docker compose config -q
```

Current results are recorded in [PROJECT_STATUS.md](PROJECT_STATUS.md). The HTTP API is described in [openapi.yaml](openapi.yaml).

---

## Scope and limitations

- Local, single-host deployment. The Compose stack mounts the Docker socket and is not hardened for exposure to untrusted networks.
- Repository analysis is heuristic static scanning of public repositories; findings are candidates until validated.
- Two deterministic code-repair strategies exist; other findings receive recommendations, not patches.
- Removing a hard-coded credential does not revoke it — rotate it with its issuer.
