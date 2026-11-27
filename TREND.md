# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 13 run(s) of 13 recorded; latest 2026-11-27T03:44Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 13 | 13 of 13 | 3.9 s | 10.5 s | 13.4 s |
| Bad image rollout | ROLLOUT_UNDO | 13 | 13 of 13 | 4.2 s | not measured | 6.0 s |
| Crashing rollout | ROLLOUT_UNDO | 13 | 13 of 13 | 0.8 s | not measured | 9.5 s |
| One bad pod of three | DELETE_POD | 13 | 13 of 13 | 5.0 s | 1.2 s | 11.7 s |
| Scaled to zero | SCALE_WORKLOAD | 13 | 13 of 13 | 2.9 s | 10.2 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 4.3 s | 11.2 s | 13.9 s | healed |
| Bad image rollout | 6.3 s | none | 8.3 s | healed |
| Crashing rollout | 0.8 s | none | 9.3 s | healed |
| One bad pod of three | 3.7 s | none | 10.3 s | healed |
| Scaled to zero | 3.6 s | 11.3 s | 14.6 s | healed |
