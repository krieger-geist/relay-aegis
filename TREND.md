# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 5 run(s) of 5 recorded; latest 2026-11-19T02:44Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 5 | 5 of 5 | 3.2 s | 9.4 s | 12.4 s |
| Bad image rollout | ROLLOUT_UNDO | 5 | 5 of 5 | 4.2 s | not measured | 6.0 s |
| Crashing rollout | ROLLOUT_UNDO | 5 | 5 of 5 | 0.7 s | not measured | 9.5 s |
| One bad pod of three | DELETE_POD | 5 | 5 of 5 | 8.9 s | 1.1 s | 11.7 s |
| Scaled to zero | SCALE_WORKLOAD | 5 | 5 of 5 | 2.9 s | 10.2 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 5.1 s | 11.8 s | 14.9 s | healed |
| Bad image rollout | 1.2 s | none | 3.3 s | healed |
| Crashing rollout | 0.7 s | none | 9.9 s | healed |
| One bad pod of three | 10.5 s | 0.5 s | 13.8 s | healed |
| Scaled to zero | 3.6 s | 11.9 s | 13.7 s | healed |
