# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 3 run(s) of 3 recorded; latest 2026-11-17T04:07Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 3 | 3 of 3 | 3.2 s | 9.4 s | 12.4 s |
| Bad image rollout | ROLLOUT_UNDO | 3 | 3 of 3 | 5.0 s | not measured | 7.4 s |
| Crashing rollout | ROLLOUT_UNDO | 3 | 3 of 3 | 0.3 s | not measured | 8.7 s |
| One bad pod of three | DELETE_POD | 3 | 3 of 3 | 8.9 s | 1.6 s | 11.4 s |
| Scaled to zero | SCALE_WORKLOAD | 3 | 3 of 3 | 2.4 s | 9.5 s | 12.4 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 3.4 s | 9.4 s | 12.4 s | healed |
| Bad image rollout | 1.5 s | none | 3.9 s | healed |
| Crashing rollout | 0.2 s | none | 7.9 s | healed |
| One bad pod of three | 8.9 s | none | 11.1 s | healed |
| Scaled to zero | 2.4 s | 9.4 s | 12.2 s | healed |
