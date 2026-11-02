# Runbooks

Each `*.yaml` file here teaches RELAY one failure type without any Java change. RELAY reads this folder at startup and
again every minute. Check what was loaded, and why any file was rejected, at `GET /api/runbooks`; reload at once with
`POST /api/runbooks/reload`.

A runbook only proposes an action. The recovery policy, the guard (attempt limit, cooldown, circuit breaker), the
action journal and post-action verification still decide whether anything runs. There is no free-form command.

```yaml
id: connection-pool-exhausted      # 3-60 lowercase letters, digits, hyphens; unique
title: Database connection pool exhausted
category: application              # optional (default: runbook)
confidence: 0.93                   # 0.1-0.99; the highest-confidence finding wins
cause: ...                         # one-sentence root cause shown on the incident
explanation: ...                   # optional
match:                             # every listed block must match; each one is cited as evidence
  failing: true                    # default true; must be true for an action that changes anything
  container:                       # the application's container
    running: false
    status: [exited, restarting]
    exitCodes: [1, 137]
    oomKilled: true
    minRestarts: 3
    healthStatus: [unhealthy]
  logs:                            # case-insensitive regular expressions (at most 10, 200 characters each)
    patterns: ['Connection is not available']
    minMatches: 1
  healthCheck:                     # the newest failed checks must all match
    outcomes: [degraded, unreachable]
    statusCodes: [503]
    errorPatterns: ['timed out']
  dependency:                      # a dependency from the dependency graph
    name: postgres                 # optional: any dependency
    unhealthy: true                # default true
action:
  type: RESTART_CONTAINER          # START_CONTAINER, RESTART_CONTAINER, RESTART_DEPENDENCY, RECONNECT_NETWORK,
                                   # INVESTIGATE or ESCALATE
  target: app                      # app, or matched-dependency (required for dependency actions)
  text: What an operator should do, shown with the recommendation.
```

The finding names the runbook file and the SHA-256 of its content, so each incident records exactly which version of
a runbook matched.
