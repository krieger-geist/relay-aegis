# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 6 run(s) of 6 recorded; latest 2026-11-20T02:30Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 6 | 6 of 6 | 2.4 s | 8.7 s | 11.9 s |
| Bad image rollout | ROLLOUT_UNDO | 6 | 6 of 6 | 4.6 s | not measured | 6.7 s |
| Crashing rollout | ROLLOUT_UNDO | 6 | 6 of 6 | 0.5 s | not measured | 9.2 s |
| One bad pod of three | DELETE_POD | 6 | 6 of 6 | 7.0 s | 1.2 s | 12.1 s |
| Scaled to zero | SCALE_WORKLOAD | 6 | 6 of 6 | 2.6 s | 9.8 s | 12.6 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 0.9 s | 7.1 s | 10.2 s | healed |
| Bad image rollout | 4.9 s | none | 7.6 s | healed |
| Crashing rollout | 0.2 s | none | 8.8 s | healed |
| One bad pod of three | 5.0 s | 1.2 s | 12.5 s | healed |
| Scaled to zero | 0.2 s | 8.4 s | 11.4 s | healed |
