# RELAY AEGIS: Road to Industry Grade

Status date: 2026-11-04. Starting point: the frozen hackathon release (see `PROJECT_STATUS.md`).

## Where RELAY fits

Industry calls this space **auto-remediation** (part of AIOps / incident response). Every team that runs software
has the same three questions, and RELAY already answers each with evidence instead of guesses:

| Question | Today in RELAY | Industry name |
|---|---|---|
| Is my running service broken, and can it fix itself safely? | Runtime self-healing | Auto-remediation, runbook automation |
| Is the cause in my code or config, and can a fix be proposed safely? | Code self-healing (sandbox → PR) | Automated remediation, AppSec fix PRs |
| Is a public endpoint broken, and at which layer? | Live URL investigation | Synthetic monitoring, network diagnostics |

The differentiator to keep at every step: **nothing is "fixed" until fresh evidence proves it**, actions are allowlisted
and policy-gated, and AI only proposes. That is exactly what stops companies trusting auto-fix tools today.

## What is already industry grade

- Deterministic diagnosis with ranked hypotheses and labelled evidence (Observed / Inferred / Hypothesis).
- Policy, guard (attempts, cooldown, circuit breaker) and post-action verification on every recovery.
- Sandbox validation, security re-scan, approver allowlist and PR-only code changes.
- 477 backend tests (including PostgreSQL integration tests), 170 frontend tests, typed frontend, Flyway migrations, OpenAPI spec.

## Gaps against industry standards

| Area | Gap today | Why a company cares |
|---|---|---|
| Engineering hygiene | Done in Phase 0 (v1.0.0): CI, licence, security policy, changelog, scanning, signed images | First thing an engineer checks in a repo |
| RELAY's own reliability | Single instance; `@Scheduled` monitoring would double-act if two ran; Docker socket mounted directly | "Who heals the healer?" and the security review |
| Observability of RELAY | Actuator only; no Prometheus metrics, traces or structured logs | Must fit into their existing monitoring |
| Platforms | Kubernetes done in Phase 3 (v1.3.0): Deployments, Helm chart, tested on kind. No cloud APIs yet | Most companies run Kubernetes or a cloud |
| Signals | Health checks, container state and logs only; corrupt data with green health is missed | Real incidents show up first in error rates, latency and user flows |
| Integrations | Done in Phase 2 (v1.2.0) and v1.5.0: Alertmanager, Sentry and webhook in; Slack (with approvals), PagerDuty and signed webhook out. Email still missing | It has to plug into the on-call workflow |
| Runbooks | Done in Phase 2 (v1.2.0): YAML runbooks in Git, validated on load | Teams want reviewed, versioned runbooks as code |
| Code healing | Three deterministic fix strategies including OSV dependency bumps (v1.5.0); personal access token | Breadth, and GitHub App auth is the standard |
| Proof | Done in v1.5.0: nightly chaos run on kind with a published trend, MTTD/MTTR scorecard, post-incident reports | MTTD/MTTR trends and SLOs are how reliability is judged |
| Product | Single tenant, local accounts; audit export done (v1.5.0); multi-tenant plan written as future work | Teams, SSO and audit export are required to adopt |

## Phased plan

Each phase ends with something demonstrable and measured, and a LinkedIn/blog post about it.

### Phase 0: Engineering hygiene (1–2 weeks) · done
- GitHub Actions: backend `mvn -B verify`, frontend lint + typecheck + tests + build, Docker image build.
- Licence (Apache-2.0 recommended for adoption), `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, semantic versions.
- Supply-chain checks: Dependabot, CodeQL, Trivy image scan; signed images published to GHCR on tag.
- Testcontainers integration tests against real PostgreSQL.

**Done when:** every push is built and tested in CI, and `v1.0.0` images are pullable.

### Phase 1: Make RELAY reliable and observable (2–3 weeks) · done (v1.1.0)
Done: metrics, tracing, JSON logs, the Grafana dashboard, the Docker socket proxies, the action journal and leader
election. Chaos-tested with two replicas: the leader was killed after recording an action's intent; the other replica
took over in 17.5 s, re-verified instead of repeating the action, and closed the incident (one container start).

- Leader election (PostgreSQL advisory lock or ShedLock) so only one instance monitors and acts; run two replicas.
- Durable action journal: intent → executed → verified recorded before and after each action, resumed after a crash.
- Docker socket proxy limited to inspect / start / stop / restart / network connect.
- Micrometer Prometheus metrics (checks, incidents, actions, verification results, MTTD, MTTR), OpenTelemetry traces,
  JSON logs, and a Grafana dashboard in the repo.

**Done when:** killing one RELAY instance mid-repair loses no incident and repeats no action (tested by chaos).

### Phase 2: Pluggable core for a wide domain (3–4 weeks) · done (v1.2.0)
Done: YAML runbooks (`runbooks/`, re-read every minute, each incident records the file's SHA-256); alerts from
Alertmanager (and Grafana's webhook contact point), Sentry and a generic webhook, with a firing alert holding its
incident open; Slack messages with Approve / Reject buttons (signed, approver allowlist, recorded as evidence, never
for Production) and a signed outbound webhook. Verified live on Compose: the `connection-pool-exhausted` runbook,
added as a file, healed its failure (detected in 5.2 s, recovered in 10.7 s) with no Java change.
Moved on: synthetic journeys and Prometheus queries to Phase 4, the Kubernetes executor to Phase 3, email and
PagerDuty to Phase 7.

Turn the engine into three interfaces so new environments are adapters, not rewrites:
- **Signal sources:** HTTP health, synthetic user journeys, Prometheus queries, log patterns, inbound webhooks
  (Alertmanager, Sentry, Grafana).
- **Executors:** Docker and Kubernetes today; cloud APIs later.
- **Notifiers / approvals:** Slack (approve / reject buttons), email, PagerDuty, generic webhook.
- **Runbooks as code:** YAML runbooks (match → evidence required → allowed actions in order → verification),
  validated on load, versioned in Git, every action still passing policy and guard.

**Done when:** a new failure type is handled by adding a runbook file, with no Java change.

### Phase 3: Kubernetes (3–4 weeks) · done (v1.3.0)
Done: delete pod, rollout restart, rollout undo and scale, each behind a policy with namespace-scoped RBAC; signals from
pod and Deployment state; a Helm chart; and an end-to-end test on `kind`. Five real failures healed and verified on
kind, each in under 47 s with fast test timings ([results](KUBERNETES.md#measured-results)).
Cut: cordon, because it needs cluster-wide permissions. Not tested on a cluster: dependency failure, because the test
application has no database (unit tests only).

- Executor actions: delete pod, rollout restart, rollout undo, scale, cordon (policy-gated, namespace-scoped RBAC).
- Signals from Kubernetes events and probes (`CrashLoopBackOff`, `OOMKilled`, `ImagePullBackOff`, readiness failures).
- Helm chart; end-to-end test on `kind` in CI with the chaos scenarios ported to Kubernetes.

**Done when:** the demo's failures heal on Kubernetes in CI, with measured times.

### Phase 4: Deeper detection (2–3 weeks)
**Status: done, released in v1.4.0.** Synthetic user journey checks; approved data quarantine through the application's own
hook; "what changed" evidence; latency baseline; error-budget (SLO) burn-rate alerts; log anomaly rates; memory and
CPU trends; and bad-deploy rollback to the last good image, pinned so Docker keeps it. Both "done when" cases were
run live in Docker: `corrupt-record` was quarantined after approval, and a broken release was rolled back and verified
19 s after approval. Not covered: Prometheus queries as a signal source, and config changes (only image changes) in
"what changed".

- Synthetic transactions (e.g. load the Orders list), which closes today's corrupt-record limit, plus a policy-gated
  data-quarantine repair (move the row aside, never delete, require approval).
- Error-rate and latency baselines (SLO burn-rate alerts), log anomaly counts, resource exhaustion trends.
- Change correlation: link incidents to the most recent deploy, config or image change ("what changed").

**Done when:** `corrupt-record` is detected and quarantined with approval, and a bad deploy is rolled back.

### Phase 5: Continuous proof and broader code healing
**Status: done, released in v1.5.0.** The original Phases 5, 6 and 7 were folded into one final phase on 2026-11-14,
keeping the items with the most value per day of work:

- Post-incident report generated from stored evidence (page, PDF via print, Markdown).
- Reliability scorecard: MTTD / MTTR per failure type and as a trend, verified share, and actions that did not fix
  anything (the false-action rate, target zero).
- Nightly chaos run in CI on `kind`, with the trend published to the `chaos-results` branch.
- OSV dependency advisories and a deterministic bump to the fixed version, verified by an OSV re-check in the sandbox.
- PagerDuty notifier (Events API v2) and an admin audit export (CSV / JSON).

SLOs and error budgets per system shipped earlier, in v1.4.0.

## Future work

Written down deliberately rather than half-built. Each needs accounts, money or a larger design, and none of it
changes how RELAY detects, diagnoses, acts and verifies.

| Item | Why it waits |
|---|---|
| Multi-tenant hosted RELAY with an outbound agent (plan below) | The largest change: tenant ids on every table, row-level security, and an agent so a public RELAY never holds access to other people's machines |
| Organisations, teams, per-system roles, OIDC single sign-on | Comes with multi-tenancy; local accounts with roles cover one team today |
| GitHub App authentication | Needs a registered GitHub App; the fine-grained personal token is scoped to allowlisted repositories meanwhile |
| AI-proposed patches for findings with no strategy | Must pass the same sandbox, tests, re-scan and approval; deterministic strategies first keeps every change explainable |
| More strategies: missing timeouts, hard-coded URLs to config | Same pattern as the three existing strategies |
| Email notifier, data retention settings | Small; Slack, PagerDuty and the signed webhook cover on-call today |
| Documentation site, one-command install, hosted read-only demo | Hosting costs; the README, `docs/` and the AWS guide cover installation |

## Multi-tenant plan (future work)

Goal: one public RELAY where anyone can sign up, connect their own systems, and never see or touch anyone else's.
Today every signed-in account sees all applications, incidents and policies, which is fine for one team and not for
strangers. Two parts, in this order:

**1. Isolation: no one sees other people's data**
- A workspace (tenant) per sign-up, with invited members and roles per workspace (owner, operator, viewer).
- A `tenant_id` on every row: applications, health checks, incidents, recovery actions, assessments, repairs, policies,
  runbooks and notifications. Every query is filtered by the caller's workspace.
- PostgreSQL row-level security as a second guard, so a missing filter in code still returns nothing from another
  workspace.
- Recovery policies, allowlists and runbooks per workspace; the instance admin keeps global limits.
- Quotas per workspace (applications, check frequency, assessments, stored history) on top of today's per-account limits.
- Tests that try to cross the boundary: workspace A reading, approving or restarting anything of workspace B fails.
- Account and data deletion on request; retention settings; a short privacy note.

**2. Connection: an agent, not inbound access**
A public RELAY cannot safely reach private networks or hold Docker or Kubernetes access to strangers' machines. So:
- A small RELAY agent runs next to the user's systems, started with one command and a workspace token.
- The agent connects outward over HTTPS; no inbound ports are opened on the user's side.
- The agent runs health checks and executes approved actions locally, limited to its own allowlist and policies. The
  hosted RELAY stores evidence and decisions and never holds credentials to the user's systems.
- Tokens are scoped to one workspace, can be rotated and revoked, and every agent action is in the audit log.
- Agent releases are signed, like today's images.

**Order of work:** workspaces and isolation, then the agent, then quotas and abuse protection, then a larger server or
managed database (RDS with backups) before inviting real users.

**Done when:** two workspaces on one instance each connect an agent, see only their own systems and incidents, heal
their own failures, and every cross-workspace attempt in the test suite is refused.

## Who works on this problem

| Kind of company | What they would value in RELAY |
|---|---|
| Observability and error tracking (Sentry, Datadog, Grafana Labs, New Relic) | Signals → verified action; Phase 1, 2 and 4 |
| Incident response (PagerDuty, incident.io, Rootly, FireHydrant) | Runbooks, approvals, post-incident evidence; Phase 2 and 6 |
| Kubernetes reliability tooling (Komodor and similar) | Phase 3 and 4 |
| Application security / fix-PR tooling (Snyk, Socket, GitHub) | Code healing; Phase 5 |
| Cloud providers' SRE teams and high-traffic product companies (fintech, commerce, delivery) | MTTR reduction with safety; Phase 3, 4 and 6 |

## Order of work

Phase 0 → 1 → 2 first: they make every later phase safe and cheap. Phase 4's synthetic check is small and can be
pulled forward for a quick visible win.
