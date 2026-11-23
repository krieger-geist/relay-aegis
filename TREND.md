# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 9 run(s) of 9 recorded; latest 2026-11-23T01:55Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 9 | 9 of 9 | 3.2 s | 9.4 s | 12.4 s |
| Bad image rollout | ROLLOUT_UNDO | 9 | 9 of 9 | 2.7 s | not measured | 5.0 s |
| Crashing rollout | ROLLOUT_UNDO | 9 | 9 of 9 | 0.7 s | not measured | 9.3 s |
| One bad pod of three | DELETE_POD | 9 | 9 of 9 | 5.0 s | 1.2 s | 11.7 s |
| Scaled to zero | SCALE_WORKLOAD | 9 | 9 of 9 | 2.9 s | 10.1 s | 12.7 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 4.7 s | 11.2 s | 14.0 s | healed |
| Bad image rollout | 1.6 s | none | 3.7 s | healed |
| Crashing rollout | 5.8 s | none | 9.3 s | healed |
| One bad pod of three | 4.0 s | none | 10.6 s | healed |
| Scaled to zero | 1.8 s | 9.1 s | 11.7 s | healed |
