# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 14 run(s) of 14 recorded; latest 2026-11-28T03:28Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 14 | 14 of 14 | 3.6 s | 10.3 s | 13.2 s |
| Bad image rollout | ROLLOUT_UNDO | 14 | 14 of 14 | 4.6 s | not measured | 6.7 s |
| Crashing rollout | ROLLOUT_UNDO | 14 | 14 of 14 | 0.8 s | not measured | 9.4 s |
| One bad pod of three | DELETE_POD | 14 | 14 of 14 | 4.8 s | 1.2 s | 11.6 s |
| Scaled to zero | SCALE_WORKLOAD | 14 | 14 of 14 | 2.9 s | 10.3 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 0.7 s | 7.1 s | 11.3 s | healed |
| Bad image rollout | 6.3 s | none | 8.8 s | healed |
| Crashing rollout | 0.2 s | none | 8.8 s | healed |
| One bad pod of three | 4.0 s | none | 11.3 s | healed |
| Scaled to zero | 2.5 s | 10.7 s | 12.5 s | healed |
