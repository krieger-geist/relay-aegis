# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 2 run(s) of 2 recorded; latest 2026-11-16T01:20Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 2 | 2 of 2 | 2.1 s | 8.8 s | 12.4 s |
| Bad image rollout | ROLLOUT_UNDO | 2 | 2 of 2 | 5.4 s | not measured | 7.6 s |
| Crashing rollout | ROLLOUT_UNDO | 2 | 2 of 2 | 0.5 s | not measured | 9.3 s |
| One bad pod of three | DELETE_POD | 2 | 2 of 2 | 8.2 s | 1.6 s | 13.1 s |
| Scaled to zero | SCALE_WORKLOAD | 2 | 2 of 2 | 3.2 s | 11.5 s | 13.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 0.9 s | 7.1 s | 11.3 s | healed |
| Bad image rollout | 5.8 s | none | 7.7 s | healed |
| Crashing rollout | 0.7 s | none | 9.9 s | healed |
| One bad pod of three | 4.5 s | 2.0 s | 11.4 s | healed |
| Scaled to zero | 1.9 s | 9.5 s | 12.4 s | healed |
