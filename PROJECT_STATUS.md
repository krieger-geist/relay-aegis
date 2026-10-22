# RELAY AEGIS — Project Status

Status date: 2026-10-21 (frozen release).

## Implemented

| Capability | State |
|---|---|
| Authentication (register, login, rotating refresh cookie, roles, login limiting) | Implemented |
| Application monitoring, incidents, health history | Implemented |
| Deterministic runtime diagnosis (pattern engine, ranked hypotheses) | Implemented |
| Policy-gated runtime recovery with guard and health verification | Implemented |
| Live URL diagnosis (layer localization, sampling, black-box limits) | Implemented |
| GitHub repository analysis (read-only static rules) | Implemented |
| Unified finding model, ranking, actionability groups, evidence correlation | Implemented |
| Deterministic patch strategies: `credential-literal-to-env`, `dockerfile-non-root-user` | Implemented |
| Disposable sandbox validation (build, tests, baseline tests, security re-scan) | Implemented |
| Unified Auto-Repair orchestration with in-context progress and repair states | Implemented |
| Approval-gated pull request creation, branch verification, idempotent reuse | Implemented |
| Optional Gemini hypotheses with deterministic fallback | Implemented |
| Markdown assessment report and reviewed email draft | Implemented |

## Final release changes (2026-10-21)

- **Separate Level 2 and Level 3 experiences.** Navigation is grouped by boundary: Runtime (Systems, Incidents, Recovery history), Code (Repositories `/codebase`, Repairs) and Live URL (Investigations `/live-url`). Each page shows its own workflow and only its own runs; a run opened on the wrong page moves to its own level, and old `/assessments?assessment=…` links redirect by kind.
- **Level 3 evidence view.** The recorded DNS → TCP → TLS → HTTP observations are shown layer by layer with each layer's detail, followed by the HTTP observations (samples, response time, second endpoint, monitoring baseline, content type, security headers) and the sampling pattern. Live URL + Repo shows the repository correlation, keeping *possibly related* apart from *caused by*. Findings are report-only; a runtime recovery links to its Level 1 incident instead of running from Level 3.
- **Level 2 hand-off.** After Auto-Repair, View Repair Details opens the existing Code Repair Journey (success and failed validation); the approval step links to Apply & Create PR; accounts that may not approve see why.
- **Notification acknowledgement, per account.** Overview's attention strip, the notification bell and the sidebar Incidents badge share one read state stored by the backend for each user (`incident_acknowledgements`, Flyway V10; `GET/POST /api/notifications/acknowledged`). Opening an incident (or Investigate) acknowledges it on every device; the incident itself is unchanged; a new incident is notified again. Read state kept by the earlier browser-only version is imported once.
- **Named boundaries instead of level numbers.** The interface says Runtime, Code and Live URL (Code → Repositories, Live URL → Investigations) instead of "Level 1/2/3", which read as a ranking; the numbers remain only as internal architecture vocabulary in this document and in code comments.
- **Overview by level.** One card per level from stored records (`GET /api/metrics/levels` for Levels 2 and 3): systems healthy, open incidents and verified recoveries; pull requests opened, sandbox-validated and blocked repairs (a PR opened on acknowledged pre-existing test failures is counted separately); live investigations and the latest target. A level that never ran says so.
- **Incident page and mobile.** Lower-ranked hypotheses and the diagnosis history fold into expandable sections (the top hypothesis stays open; an alternative the AI picked opens itself). Every page fits a 375 px phone without horizontal scrolling.
- **Truthfulness fixes.** "Currently healthy" counts only systems observed in the last 24 hours (it could exceed "connected"); systems not observed for 24 hours show *Unknown* with the date of their last check; URL monitoring is *Connected* only while a monitored system was checked in the last 24 hours; the GitHub integration describes the approved pull-request flow; the non-existent "Additional telemetry (planned)" connector was removed; the assessment summary omits a "0 auto-repairable" count.
- **Live URL: what changed and where the time goes.** Each Live URL run stores a structured observation (Flyway V11 `live_observation_json`: per-layer result, DNS/TCP/TLS timing, per-request time to first byte and download time, TLS protocol, certificate issuer and expiry, tracked security-header presence, redirect target without query, server and content type; never a body). `GET /api/assessments/{id}/live` returns it with the comparison to the previous completed run of the same endpoint: a change is reported only as a difference between two recorded values; latency counts only when it moves by both 1.5x and 100 ms. Runs recorded before this release are reported as not comparable. Verified live: example.com twice (compared, no change) and github.com twice (median first byte 619 → 233 ms, flagged). Code and Runtime are unchanged.
- **Runtime → Code: root cause in code.** A new deterministic Runtime rule, *credentials rejected by a dependency* (`dependency-credentials-rejected`), fires only when the application is failing and its logs show a dependency refusing its credentials (e.g. PostgreSQL `password authentication failed`). It recommends no runtime action, because a restart reuses the same credential. A hard-coded credential found by Code in the system's own repository is cited as unverified code evidence. `GET /api/incidents/{id}/code-link` (read-only) links the incident to that Code finding; the incident and System pages show its repair state and close the loop only when the fix is merged, verified on the default branch, and the system is healthy again ("fixed by PR #N"). The repair itself runs unchanged in Code. Demonstration target: `relay-aegis-orders-service` (separate repository). Verified live: password rotation → incident in ~48 s → top diagnosis 0.98 with the real log line, no runtime action → reset → recovered. The four SBA scenarios are unchanged (scenario tests pass; their logs never produce the new signal).
- **Live URL: certificate evidence.** The probe's own TLS handshake records the presented certificate through a recording trust manager that wraps the JDK validator and changes no decision: subject, SANs, issuer, validity dates, days remaining, SHA-256 fingerprint, key, signature algorithm, the chain as presented, TLS version and cipher, plus host-name match (RFC 6125, SANs before CN) and chain trust as separate results. A rejected certificate is still recorded; plain HTTP is NOT_TESTED; a handshake with no certificate is UNAVAILABLE. Stored in the live observation, so the next run reports a different certificate or a changed host-name/chain result. No key or session material is stored. Verified live: example.com, github.com, a plain-HTTP target, and expired / wrong-host / self-signed certificates on badssl.com.
- **Any public website (opt-in).** `RELAY_ASSESSMENT_ALLOW_PUBLIC_HOSTS=true` lets Live URL investigate any host that resolves only to public addresses; off by default. The host is re-resolved and checked before every HTTP request, closing a DNS-rebinding gap where the HTTP client resolved the name separately from the TCP/TLS probe. The form shows the active policy (`GET /api/assessments/target-policy`), and the scope note now says a few bounded GETs are made (it previously claimed one).
- **Data.** The two Smart Business AI Assistant registrations are named by role (Frontend, Backend). Monitoring was disabled (not deleted) for three stale registrations: two pointing at the removed `demo-target` host and one Smart Business AI Assistant entry registered at `localhost:3001`, which is unreachable from the backend container. Their history is kept.

## Three operating levels

| Level | Evidence boundary | Action |
|---|---|---|
| **1 · Runtime self-healing** (Systems) | Health checks, container state and health, logs, dependencies (Compose `depends_on`) | Allowlisted, policy-checked runtime recovery, then verification. Never changes source code. |
| **2 · Codebase self-healing** (Investigate → Codebase) | Repository analysis | Patch → sandbox build/tests → re-scan → approval → pull request |
| **3 · Live URL investigation** (Investigate → Live URL, optional repository) | DNS/TCP/TLS/HTTP, headers, samples; repository as extra evidence | Structured report only; code repair is refused server-side |

**Level 1 rehearsal on Smart Business AI Assistant** (`sba-frontend` → `sba-backend` → `sba-postgres`, 2026-10-20):
- Backend stopped: detected in ~16 s, diagnosed `container-stopped` (0.99), operator-approved `START_CONTAINER`, verified healthy after 51 s (2 consecutive checks; Docker healthy; `/actuator/health` UP).
- PostgreSQL stopped while the backend kept running: diagnosed `dependency-unavailable` (0.99) on `sba-postgres` from its exited state and the backend log `UnknownHostException: postgres`, ranked above "restart the backend"; recovery correctly **blocked** because the dependency-restart policy is disabled.
- Backend process crash restarted by Docker: detected and closed with no RELAY action, labelled "recovered without a RELAY repair".
- PostgreSQL stopped, dependency policy enabled for the rehearsal only (Requires approval): RELAY restarted `sba-postgres` itself after operator approval and resolved after 2 consecutive healthy checks (~21 s); policy restored to Disabled.
- Backend process frozen (SIGSTOP) while its container stayed running: diagnosed `application-timeout` (TCP connects, HTTP never answers), operator-approved `RESTART_CONTAINER`, resolved after 3 healthy checks (~65 s from approval).
- Network partition: `sba-postgres` detached from `smart-business-ai_default` while both containers stayed healthy. Diagnosed `dependency-network-detached` (0.99), ranked above "restart the backend" (0.86); network-reconnect policy enabled for the rehearsal only (Requires approval); RELAY reconnected `sba-postgres` with its service alias `postgres` and resolved after consecutive healthy checks (~21 s from approval); policy restored to Disabled. The backend was not restarted.

**Level 2 live runs on `krieger-geist/relay-aegis-remediation-test`** (2026-10-20):
- *Success* (assessment ASM-9A8E6D5B-52B, finding F-2e6a8b4f, patch PATCH-965A7ABC6A): change scope 1 file +1 −1; sandbox Maven build and tests passed; re-scan 1 → 0; approved; branch `relay-aegis/fix/F-2e6a8b4f`, commit `1412d48`, pull request #3. Post-publication verification: branch file matches the reviewed patch, the branch changes exactly one file, `main` unchanged. GitHub: PR open, not merged, no auto-merge. Repeating Apply returned PR #3. State: *Repair attempted* (human merge pending).
- *Failure* (commit pinned to `5c4588a`, which does not compile; patch PATCH-3A92D62A99): patched build failed and the unpatched baseline build failed too, so the failure is reported as pre-existing; validation failed, Apply unavailable, a direct publish attempt returned 422, and the repository's refs were byte-identical before and after.
- A GitHub repair becomes *Resolved* only when a human merged the PR and a re-scan of the default branch no longer finds the issue.

## Validated live (real Docker, real GitHub)

**Runtime self-healing.** The allowlisted `relay-aegis-test-target` container was stopped. RELAY opened an incident, diagnosed *Application container is stopped* (0.99), required operator approval, executed `START_CONTAINER`, observed two consecutive passing health checks and marked the recovery verified. Docker reported the container running and healthy; `/health` returned `UP`.

**GitHub Auto-Repair** on `krieger-geist/relay-aegis-remediation-test` (assessment ASM-0670243D-F09, commit `41e37ae`):
- Finding *Possible hard-coded credential*, `Todo.java:18`, classified Auto-repairable.
- Safety checks, finding re-location, sandbox checkout and in-sandbox patch: passed.
- Maven build and tests on the patched commit: passed. Security re-scan: targeted finding 1 → 0, no new finding.
- Approved and applied: branch `relay-aegis/fix/F-77f968f1`, commit `38af15b`, pull request #2 with exactly one changed line. Default branch unchanged, PR not merged, repeat request reused the PR. Final state: *Repair attempted* (not resolved — merging is a human decision).

**Safety blocking.** Earlier, when the demo repository's tests failed with and without the patch, RELAY classified the proposal as not validated and refused to create a pull request.

## Automated test status

| Suite | Result |
|---|---|
| Backend (`mvn -B clean test`) | 423 tests, 0 failures, 0 errors |
| Frontend (`vitest`) | 25 files, 170 tests passed |
| TypeScript (`tsc --noEmit`) | Passed |
| ESLint | 0 errors, 0 warnings |
| Production build (`vite build`) | Passed |
| `docker compose config -q` | Passed |

## Known limitations

- Local single-host deployment; the Docker socket mount is not suitable for untrusted networks.
- Repository rules are heuristic. Known gap: the credential rule does not flag every naming pattern (for example a `*_SECRET_KEY` constant).
- Code repair is limited to the two deterministic strategies; the Dockerfile strategy is covered by automated tests but has not been exercised against a live repository.
- Sandbox runs can take several minutes for Maven projects (no shared dependency cache by design).
- Removing a credential literal does not revoke it; rotation stays with the credential's issuer.
- Level 3 observes one endpoint (plus the origin root) from outside; it cannot see internal health beyond what the endpoint returns.
