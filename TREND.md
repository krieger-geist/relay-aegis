# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 4 run(s) of 4 recorded; latest 2026-11-18T03:05Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 4 | 4 of 4 | 2.4 s | 8.7 s | 11.9 s |
| Bad image rollout | ROLLOUT_UNDO | 4 | 4 of 4 | 4.6 s | not measured | 6.7 s |
| Crashing rollout | ROLLOUT_UNDO | 4 | 4 of 4 | 0.5 s | not measured | 9.1 s |
| One bad pod of three | DELETE_POD | 4 | 4 of 4 | 6.7 s | 1.2 s | 11.6 s |
| Scaled to zero | SCALE_WORKLOAD | 4 | 4 of 4 | 2.6 s | 9.8 s | 12.6 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 1.5 s | 7.9 s | 10.6 s | healed |
| Bad image rollout | 4.2 s | none | 6.0 s | healed |
| Crashing rollout | 3.5 s | none | 9.5 s | healed |
| One bad pod of three | 3.5 s | 1.1 s | 11.7 s | healed |
| Scaled to zero | 2.9 s | 10.2 s | 12.8 s | healed |
