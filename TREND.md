# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 10 run(s) of 10 recorded; latest 2026-11-24T05:28Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 10 | 10 of 10 | 3.3 s | 9.9 s | 12.9 s |
| Bad image rollout | ROLLOUT_UNDO | 10 | 10 of 10 | 3.5 s | not measured | 5.5 s |
| Crashing rollout | ROLLOUT_UNDO | 10 | 10 of 10 | 0.7 s | not measured | 9.4 s |
| One bad pod of three | DELETE_POD | 10 | 10 of 10 | 7.0 s | 1.2 s | 11.8 s |
| Scaled to zero | SCALE_WORKLOAD | 10 | 10 of 10 | 2.8 s | 10.1 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 5.1 s | 12.2 s | 14.9 s | healed |
| Bad image rollout | 5.4 s | none | 7.6 s | healed |
| Crashing rollout | 0.5 s | none | 10.0 s | healed |
| One bad pod of three | 11.1 s | none | 14.8 s | healed |
| Scaled to zero | 2.7 s | 10.7 s | 13.6 s | healed |
