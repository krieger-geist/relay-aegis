# Chaos results: without healing vs with healing

Measured on 23 Oct 2026 with [`chaos.ps1`](chaos.ps1) against Smart Business AI Assistant (`sba-frontend` →
`sba-backend` → `sba-postgres`). Raw rows: [`results/chaos-results.csv`](results/chaos-results.csv).

- **Outage** is measured by the script itself, independently of RELAY: it polls the backend's own health endpoint
  (`/actuator/health`) every second and records when it went down and when it came back.
- **Detected in / Recovered in** are RELAY's own incident records (`detection_duration_ms`, `recovery_duration_ms`).
- **With healing:** RELAY's recovery policies set to *Automatic* (confirmed with `CONFIRM-AUTO-REPAIR`); no human
  action during the run. **Without healing:** the same policies set to *Requires approval*, and nobody approved.
- Configuration: health check every 5 s, incident after 2 consecutive failures, closed after 2 healthy checks.
  SBA's health endpoint itself takes 3–4 s per call (Spring checks its SMTP server), which the probes allow for.
- Single runs, not averages.

## Failures RELAY heals

| Failure | Without healing | With healing: RELAY action | Detected in | Recovered in | Outage |
|---|---|---|---|---|---|
| `stop-backend` | still down after 90 s | `START_CONTAINER` sba-backend | 5.3 s | 30.4 s | **33.1 s** |
| `crash-backend` | back after 30.5 s (Docker's restart policy) | `START_CONTAINER` sba-backend | 5.2 s | 25.1 s | **29.4 s** |
| `freeze-backend` | still down after 90 s | `RESTART_CONTAINER` sba-backend | 11.2 s | 33.8 s | **46.1 s** |
| `stop-db` | still down after 90 s | `RESTART_DEPENDENCY` sba-postgres | 11.2 s | 18.4 s | **27.7 s** |
| `block-db` | still down after 90 s | `RECONNECT_NETWORK` sba-postgres (alias `postgres`) | 11.1 s | 15.2 s | **30.6 s** |

Without healing RELAY still detected every failure (5.1–11.5 s) and diagnosed it; it waited for an approval that
never came. Four of five failures stayed down for the whole 90 s window.

**The 30-second target:** met for `stop-db` and `crash-backend`, close for `block-db` and `stop-backend`, missed for
`freeze-backend`. RELAY's part (detection 5–11 s plus the action) is short; most of the remaining time is SBA's own
Spring Boot start-up (about 20–25 s) after a container start or restart, and the freeze needs a timed-out health check
before it counts as down.

## Failures RELAY cannot heal (yet)

| Failure | What happened | Why |
|---|---|---|
| `corrupt-record` | Health stayed green for the whole run; no incident. The Orders page for that account fails. | RELAY watches health endpoints, containers, logs and networks. Bad data inside a healthy app is invisible to all four, and no restart could repair it. Detecting it needs application-level checks (for example a synthetic request to `/api/orders`). |
| `rotate-password` | Incident in 8.1 s; diagnosed as *credentials rejected by a dependency*; **no runtime action**; still down after 90 s. | A restart would reuse the same hard-coded password. RELAY refuses the wrong fix and links the incident to the hard-coded credential that Code found; the fix is a reviewed pull request, merged by a human. |
| `flood` | 20 000 requests, 300 at a time: health stayed green (median 3.2 s, same as idle); no incident. | Nothing failed, so nothing to heal. RELAY has no rate-limiting or scaling action; a flood large enough to exhaust the backend would be detected as an outage and answered with a restart, which does not stop the traffic. |
