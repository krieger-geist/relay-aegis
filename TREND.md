# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 1 run(s) of 1 recorded; latest 2026-11-15T01:14Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 1 | 1 of 1 | 3.2 s | 10.5 s | 13.5 s |
| Bad image rollout | ROLLOUT_UNDO | 1 | 1 of 1 | 5.0 s | not measured | 7.4 s |
| Crashing rollout | ROLLOUT_UNDO | 1 | 1 of 1 | 0.3 s | not measured | 8.7 s |
| One bad pod of three | DELETE_POD | 1 | 1 of 1 | 11.9 s | 1.2 s | 14.7 s |
| Scaled to zero | SCALE_WORKLOAD | 1 | 1 of 1 | 4.5 s | 13.5 s | 15.2 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 3.2 s | 10.5 s | 13.5 s | healed |
| Bad image rollout | 5.0 s | none | 7.4 s | healed |
| Crashing rollout | 0.3 s | none | 8.7 s | healed |
| One bad pod of three | 11.9 s | 1.2 s | 14.7 s | healed |
| Scaled to zero | 4.5 s | 13.5 s | 15.2 s | healed |
