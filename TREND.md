# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 11 run(s) of 11 recorded; latest 2026-11-25T04:30Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 11 | 11 of 11 | 3.4 s | 10.1 s | 12.9 s |
| Bad image rollout | ROLLOUT_UNDO | 11 | 11 of 11 | 2.7 s | not measured | 5.0 s |
| Crashing rollout | ROLLOUT_UNDO | 11 | 11 of 11 | 0.7 s | not measured | 9.5 s |
| One bad pod of three | DELETE_POD | 11 | 11 of 11 | 5.0 s | 1.2 s | 11.7 s |
| Scaled to zero | SCALE_WORKLOAD | 11 | 11 of 11 | 2.9 s | 10.1 s | 12.7 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 3.9 s | 10.1 s | 12.9 s | healed |
| Bad image rollout | 1.8 s | none | 3.7 s | healed |
| Crashing rollout | 5.9 s | none | 10.2 s | healed |
| One bad pod of three | 3.2 s | 1.1 s | 10.8 s | healed |
| Scaled to zero | 3.1 s | 10.1 s | 12.7 s | healed |
