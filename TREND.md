# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 7 run(s) of 7 recorded; latest 2026-11-21T02:43Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 7 | 7 of 7 | 3.0 s | 9.2 s | 12.0 s |
| Bad image rollout | ROLLOUT_UNDO | 7 | 7 of 7 | 4.2 s | not measured | 6.0 s |
| Crashing rollout | ROLLOUT_UNDO | 7 | 7 of 7 | 0.7 s | not measured | 9.5 s |
| One bad pod of three | DELETE_POD | 7 | 7 of 7 | 8.9 s | 1.2 s | 11.7 s |
| Scaled to zero | SCALE_WORKLOAD | 7 | 7 of 7 | 2.9 s | 10.2 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 3.0 s | 9.2 s | 12.0 s | healed |
| Bad image rollout | 2.7 s | none | 5.0 s | healed |
| Crashing rollout | 3.0 s | none | 10.1 s | healed |
| One bad pod of three | 9.2 s | 2.7 s | 11.3 s | healed |
| Scaled to zero | 3.1 s | 10.4 s | 14.3 s | healed |
