# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 8 run(s) of 8 recorded; latest 2026-11-22T01:30Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 8 | 8 of 8 | 3.1 s | 9.3 s | 12.2 s |
| Bad image rollout | ROLLOUT_UNDO | 8 | 8 of 8 | 3.5 s | not measured | 5.5 s |
| Crashing rollout | ROLLOUT_UNDO | 8 | 8 of 8 | 0.7 s | not measured | 9.4 s |
| One bad pod of three | DELETE_POD | 8 | 8 of 8 | 7.0 s | 1.2 s | 11.8 s |
| Scaled to zero | SCALE_WORKLOAD | 8 | 8 of 8 | 2.9 s | 10.1 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 4.7 s | 11.4 s | 13.9 s | healed |
| Bad image rollout | 1.8 s | none | 4.1 s | healed |
| Crashing rollout | 5.9 s | none | 9.3 s | healed |
| One bad pod of three | 3.7 s | none | 11.8 s | healed |
| Scaled to zero | 2.9 s | 10.1 s | 12.7 s | healed |
