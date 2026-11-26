# Nightly chaos trend

Every night CI breaks a real Kubernetes workload on kind in 5 ways and measures, from outside RELAY,
how long detection and healing took. Last 12 run(s) of 12 recorded; latest 2026-11-26T03:43Z.

| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |
|---|---|---|---|---|---|---|
| All pods stuck | RESTART_CONTAINER | 12 | 12 of 12 | 3.6 s | 10.3 s | 13.2 s |
| Bad image rollout | ROLLOUT_UNDO | 12 | 12 of 12 | 3.5 s | not measured | 5.5 s |
| Crashing rollout | ROLLOUT_UNDO | 12 | 12 of 12 | 0.8 s | not measured | 9.6 s |
| One bad pod of three | DELETE_POD | 12 | 12 of 12 | 7.0 s | 1.2 s | 11.8 s |
| Scaled to zero | SCALE_WORKLOAD | 12 | 12 of 12 | 2.9 s | 10.1 s | 12.8 s |

## Runs that did not heal

None in this window.

## Latest run

| Failure | Detect | Outage | Healed after | Result |
|---|---|---|---|---|
| All pods stuck | 3.9 s | 10.5 s | 13.4 s | healed |
| Bad image rollout | 5.6 s | none | 7.6 s | healed |
| Crashing rollout | 0.9 s | none | 9.7 s | healed |
| One bad pod of three | 9.2 s | 1.2 s | 13.0 s | healed |
| Scaled to zero | 5.1 s | 12.9 s | 16.4 s | healed |
