# Running RELAY on Kubernetes

RELAY can watch and repair Deployments in a cluster, using the same loop as on Docker: detect, gather evidence,
diagnose, check policy, act, verify. It runs inside the cluster with permissions only in the namespaces you list.

## Install

The chart is in [`deploy/helm/relay-aegis`](../deploy/helm/relay-aegis). Build and push the images first; the defaults
name local images.

```yaml
# my-values.yaml
image:
  backend: registry.example.com/relay-aegis-backend:1.3.0
  frontend: registry.example.com/relay-aegis-frontend:1.3.0
kubernetes:
  namespaces: [shop]                          # RELAY gets a Role in these and nowhere else
  allowedWorkloads: ["k8s:shop/checkout"]     # Deployments RELAY may change
  inspectWorkloads: ["k8s:shop/database"]     # Deployments RELAY may only read
  dependencies: "k8s:shop/checkout=k8s:shop/database"
  restoreReplicas: 2                          # used when a workload is found at zero replicas
monitoring:
  allowedHosts: [checkout.shop.svc.cluster.local]
auth:
  adminEmails: you@example.com
  corsAllowedOrigins: "http://localhost:18081"   # the address you open RELAY at; sign-in is refused from any other
```

```bash
helm upgrade --install relay deploy/helm/relay-aegis -n relay-system --create-namespace -f my-values.yaml
```

The chart creates no Ingress. To open the dashboard from your machine:

```bash
kubectl -n relay-system port-forward svc/relay-frontend 18081:80
```

Open http://localhost:18081, then add the application with its in-cluster URL and, in the container field, the target
`k8s:shop/checkout`. If you see "Cannot reach the RELAY AEGIS API", check that the address you opened is listed in
`auth.corsAllowedOrigins`.

The built-in database has no persistent storage by default, so a restart loses RELAY's data. It is for trying RELAY
out. For real use, set `postgres.enabled: false` and fill in `externalDatabase`.

## What it can do

| Action | What it does on the cluster | Policy |
|---|---|---|
| `RESTART_CONTAINER`, `RESTART_DEPENDENCY` | Rollout restart, or scale up if the workload is at zero | `policy-restart` and `policy-dependency-restart`, as on Docker |
| `ROLLOUT_UNDO` | Back to the previous revision | `policy-kubernetes-rollback` |
| `SCALE_WORKLOAD` | Scale from zero to `restoreReplicas` | `policy-kubernetes-scale` |
| `DELETE_POD` | Delete the worst unready pod, so the Deployment replaces it | `policy-kubernetes-delete-pod` |

The three Kubernetes policies start at `require_approval`. RELAY acts by itself only after an operator changes a policy
to `allow_automatically`. Cooldowns, attempt limits, the action journal and verification all work as they do on Docker.

## What it recognises

| Finding | Seen as | Recommended action |
|---|---|---|
| `kubernetes-scaled-to-zero` | Desired replicas are 0 | `SCALE_WORKLOAD` |
| `kubernetes-image-pull` | `ImagePullBackOff`, `ErrImagePull`, `InvalidImageName` | `ROLLOUT_UNDO`, or escalate if there is no earlier revision |
| `kubernetes-bad-rollout` | Pods failing within 30 minutes of a rollout | `ROLLOUT_UNDO` |
| `kubernetes-unschedulable` | Pods cannot be scheduled | Escalate to a person: restarting cannot help |
| `kubernetes-pod-unready` | One pod of several is unready | `DELETE_POD` |
| `kubernetes-pods-unready` | Every pod is unready | `RESTART_CONTAINER` |

The leader polls watched Deployments (`kubernetes.signalIntervalMs`). A real failure such as `CrashLoopBackOff` or
`OOMKilled` opens an incident even while the health URL still answers, and holds it open until the failure clears. Pods
that are simply starting (`ContainerCreating`, `PodInitializing`) are not treated as failures.

## Permissions

For each namespace in `kubernetes.namespaces`, the chart creates a Role and a RoleBinding with only:

| Resource | Verbs |
|---|---|
| `deployments` | get, list, patch, update |
| `deployments/scale` | get, patch, update |
| `replicasets` | get, list |
| `pods` | get, list, delete |
| `pods/log` | get |
| `events` | list |

There is no ClusterRole. Even inside those namespaces, RELAY refuses to change any Deployment that is not in
`allowedWorkloads`. A target must be written exactly as `k8s:<namespace>/<deployment>`.

## Measured results

[`scripts/k8s-e2e.sh`](../scripts/k8s-e2e.sh) creates a [kind](https://kind.sigs.k8s.io/) cluster, installs the chart,
breaks a real Deployment five ways and times the recovery from outside RELAY. It fails unless each recovery reached the
`verified` phase and the Deployment is fully ready again.

Run of 2026-11-06, Kubernetes v1.34.0 on one kind node ([full record](../chaos/results/kubernetes-20261106-232629.md)):

| Failure | Action taken | Detected after | Outage seen from outside | Healed after |
|---|---|---|---|---|
| Scaled to zero | `SCALE_WORKLOAD` | 2.0 s | 42.9 s | 46.9 s |
| Bad image rollout | `ROLLOUT_UNDO` | 10.8 s | none | 31.6 s |
| Crashing rollout | `ROLLOUT_UNDO` | 10.5 s | none | 14.3 s |
| All pods stuck | `RESTART_CONTAINER` | 4.3 s | 11.6 s | 16.8 s |
| One bad pod of three | `DELETE_POD` | 14.0 s | none | 24.8 s |

Read these with care:

- The test polls every 5 seconds, with a 5-second cooldown and all policies set to `allow_automatically`. The chart's
  defaults are 60 seconds for monitoring, 15 seconds for signals and `require_approval` for actions, so a default install
  detects and acts more slowly.
- The test application starts in seconds. A slower application takes longer to heal.
- Times vary between runs on the same laptop. Scaled to zero showed about 15 s of outage in two earlier runs and
  42.9 s in this one, so treat these as one sample and not as a benchmark.
- Rollouts show no outage because the old pod keeps serving until the new one is ready. RELAY's job there is to stop a
  bad rollout from staying stuck.
- "Verified" requires the same completion test as `kubectl rollout status`, plus enough live ready pods and two
  passing health checks. Old pods still answering are not enough.
- With one bad pod of three, detection depends on which pod the health check reaches, so it varies between runs.

```bash
scripts/k8s-e2e.sh            # full run
SKIP_SETUP=1 scripts/k8s-e2e.sh   # against the cluster from an earlier run
scripts/k8s-e2e.sh --delete   # remove the cluster
```

The same test runs in CI ([`kubernetes-e2e.yml`](../.github/workflows/kubernetes-e2e.yml)).

## Limits

- Deployments only, not StatefulSets or DaemonSets. Multi-container pods are treated by their first failing container.
- No node actions. Cordon was planned but needs cluster-wide permissions, which RELAY deliberately does not ask for.
- A failed dependency is covered by unit tests only, because the test application has no database.
- Tested on kind with one node, not on a managed cloud cluster or under production load.
- `ROLLOUT_UNDO` restores the previous revision of the Deployment only. It does not undo database migrations or changed
  ConfigMaps and Secrets.
