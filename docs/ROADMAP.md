# RELAY AEGIS: Road to Industry Grade

Status date: 2026-10-29. Starting point: the frozen hackathon release (see `PROJECT_STATUS.md`).

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
- 425 backend tests (including PostgreSQL integration tests), 170 frontend tests, typed frontend, Flyway migrations, OpenAPI spec.

## Gaps against industry standards

| Area | Gap today | Why a company cares |
|---|---|---|
| Engineering hygiene | Done in Phase 0 (v1.0.0): CI, licence, security policy, changelog, scanning, signed images | First thing an engineer checks in a repo |
| RELAY's own reliability | Single instance; `@Scheduled` monitoring would double-act if two ran; Docker socket mounted directly | "Who heals the healer?" and the security review |
| Observability of RELAY | Actuator only; no Prometheus metrics, traces or structured logs | Must fit into their existing monitoring |
| Platforms | Docker on one host only | Most companies run Kubernetes or a cloud |
| Signals | Health checks, container state and logs only; corrupt data with green health is missed | Real incidents show up first in error rates, latency and user flows |
| Integrations | No Slack, PagerDuty, Alertmanager, Sentry or webhook in/out | It has to plug into the on-call workflow |
| Runbooks | Recovery actions and policy live in code and env variables | Teams want reviewed, versioned runbooks as code |
| Code healing | Two fix strategies; personal access token | Breadth, and GitHub App auth is the standard |
| Proof | Results from rehearsals, not continuous measurement | MTTD/MTTR trends and SLOs are how reliability is judged |
| Product | Single tenant, local accounts | Teams, SSO and audit export are required to adopt |

## Phased plan

Each phase ends with something demonstrable and measured, and a LinkedIn/blog post about it.

### Phase 0: Engineering hygiene (1–2 weeks) · done
- GitHub Actions: backend `mvn -B verify`, frontend lint + typecheck + tests + build, Docker image build.
- Licence (Apache-2.0 recommended for adoption), `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, semantic versions.
- Supply-chain checks: Dependabot, CodeQL, Trivy image scan; signed images published to GHCR on tag.
- Testcontainers integration tests against real PostgreSQL.

**Done when:** every push is built and tested in CI, and `v1.0.0` images are pullable.

### Phase 1: Make RELAY reliable and observable (2–3 weeks)
Progress: metrics, tracing, JSON logs, the Grafana dashboard, the Docker socket proxies and the action journal are
done; leader election follows.

- Leader election (PostgreSQL advisory lock or ShedLock) so only one instance monitors and acts; run two replicas.
- Durable action journal: intent → executed → verified recorded before and after each action, resumed after a crash.
- Docker socket proxy limited to inspect / start / stop / restart / network connect.
- Micrometer Prometheus metrics (checks, incidents, actions, verification results, MTTD, MTTR), OpenTelemetry traces,
  JSON logs, and a Grafana dashboard in the repo.

**Done when:** killing one RELAY instance mid-repair loses no incident and repeats no action (tested by chaos).

### Phase 2: Pluggable core for a wide domain (3–4 weeks)
Turn the engine into three interfaces so new environments are adapters, not rewrites:
- **Signal sources:** HTTP health, synthetic user journeys, Prometheus queries, log patterns, inbound webhooks
  (Alertmanager, Sentry, Grafana).
- **Executors:** Docker today; Kubernetes next; cloud APIs later.
- **Notifiers / approvals:** Slack (approve / reject buttons), email, PagerDuty, generic webhook.
- **Runbooks as code:** YAML runbooks (match → evidence required → allowed actions in order → verification),
  validated on load, versioned in Git, every action still passing policy and guard.

**Done when:** a new failure type is handled by adding a runbook file, with no Java change.

### Phase 3: Kubernetes (3–4 weeks)
- Executor actions: delete pod, rollout restart, rollout undo, scale, cordon (policy-gated, namespace-scoped RBAC).
- Signals from Kubernetes events and probes (`CrashLoopBackOff`, `OOMKilled`, `ImagePullBackOff`, readiness failures).
- Helm chart; end-to-end test on `kind` in CI with the chaos scenarios ported to Kubernetes.

**Done when:** the demo's failures heal on Kubernetes in CI, with measured times.

### Phase 4: Deeper detection (2–3 weeks)
- Synthetic transactions (e.g. load the Orders list), which closes today's corrupt-record limit, plus a policy-gated
  data-quarantine repair (move the row aside, never delete, require approval).
- Error-rate and latency baselines (SLO burn-rate alerts), log anomaly counts, resource exhaustion trends.
- Change correlation: link incidents to the most recent deploy, config or image change ("what changed").

**Done when:** `corrupt-record` is detected and quarantined with approval, and a bad deploy is rolled back.

### Phase 5: Broader code healing (3–4 weeks)
- GitHub App authentication instead of a personal token.
- New deterministic strategies: vulnerable dependency bumps (OSV data), missing timeouts, hard-coded URLs to config.
- AI-proposed patches for findings without a strategy, gated by the same sandbox, tests, re-scan and approval.
- Each fix PR adds a regression test that reproduces the original failure.

### Phase 6: Continuous proof (ongoing)
- Nightly chaos run in CI on `kind`; MTTD / MTTR per failure type published as a trend.
- SLOs and error budgets per system; generated post-incident report from stored evidence.
- Public benchmark page: failure types, detection and recovery times, false-action rate (target: zero).

### Phase 7: Product readiness
- Organisations, teams and role-based access per system; OIDC single sign-on.
- Audit log export; data retention settings; hardened deployment guide.
- Documentation site, one-command install, hosted read-only demo.

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
