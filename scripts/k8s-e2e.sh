#!/usr/bin/env bash
# Breaks a real Deployment on a kind cluster in five ways and proves RELAY heals each one. Times are measured here,
# from outside RELAY. Requires docker, kind, kubectl and helm. Fails if any scenario does not heal.
#
#   scripts/k8s-e2e.sh            full run: creates the cluster, builds images, installs, tests
#   SKIP_SETUP=1 scripts/k8s-e2e.sh    test against a cluster already set up by an earlier run
#   scripts/k8s-e2e.sh --delete   deletes the cluster
set -uo pipefail
export MSYS_NO_PATHCONV=1

CLUSTER=relay-e2e
RELEASE=relay
# On Git Bash for Windows, a native path: path conversion is off above because it would mangle the API paths below.
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
NS=relay-system
DEMO=demo
TIMEOUT=${SCENARIO_TIMEOUT:-180}
RESULTS="$ROOT/chaos/results/kubernetes-$(date +%Y%m%d-%H%M%S).md"
PORT=18080
E2E_EMAIL=e2e@relay.test
# A throwaway account on a throwaway cluster, fixed so a re-run with SKIP_SETUP can sign in again.
E2E_PASSWORD='E2e-Only-Relay-Test-1!'
failed=0

step() { echo "[$(date +%H:%M:%S)] $*"; }

if [ "${1:-}" = "--delete" ]; then kind delete cluster --name "$CLUSTER"; exit $?; fi

setup() {
  kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s || exit 1
  step "Building images"
  docker build -q -t relay-aegis-backend:local "$ROOT/backend" || exit 1
  docker build -q -t relay-aegis-frontend:local "$ROOT/frontend" || exit 1
  docker build -q -t relay-aegis-test-target:local "$ROOT/test-target" || exit 1
  step "Loading images into the cluster"
  kind load docker-image --name "$CLUSTER" relay-aegis-backend:local relay-aegis-frontend:local relay-aegis-test-target:local || exit 1
  kubectl apply -f "$ROOT/deploy/kind/test-target.yaml" || exit 1
  kubectl -n "$DEMO" rollout status deploy/test-target --timeout=120s || exit 1
  step "Installing RELAY with Helm"
  local upgrade=0
  helm status "$RELEASE" -n "$NS" >/dev/null 2>&1 && upgrade=1
  helm upgrade --install "$RELEASE" "$ROOT/deploy/helm/relay-aegis" -n "$NS" --create-namespace -f "$ROOT/deploy/kind/values-e2e.yaml" --wait --timeout 6m || exit 1
  # The image tag never changes, so Helm sees no difference after a rebuild: restart to pick up the newly loaded images.
  if [ "$upgrade" = 1 ]; then
    kubectl -n "$NS" rollout restart "deploy/$RELEASE-backend" "deploy/$RELEASE-frontend" || exit 1
    kubectl -n "$NS" rollout status "deploy/$RELEASE-backend" --timeout=5m || exit 1
  fi
}

sql() { kubectl -n "$NS" exec "deploy/$RELEASE-postgres" -- psql -U relay -d relay_aegis -Atc "$1"; }

# Through the API server's service proxy: independent of RELAY and of any one pod's port-forward.
target_healthy() { [ "$(kubectl get --raw "/api/v1/namespaces/$DEMO/services/test-target:3000/proxy/health" 2>/dev/null | grep -c '"UP"')" = "1" ]; }

api() {
  local method=$1 path=$2 body=${3:-}
  curl -sS -m 15 -X "$method" "http://127.0.0.1:$PORT$path" -H "Content-Type: application/json" -H "X-Relay-Client: e2e" \
    ${TOKEN:+-H "Authorization: Bearer $TOKEN"} ${body:+-d "$body"}
}

register() {
  kubectl -n "$NS" port-forward "deploy/$RELEASE-backend" "$PORT:8080" >/dev/null 2>&1 &
  FORWARD=$!
  trap 'kill $FORWARD 2>/dev/null' EXIT
  for _ in $(seq 1 30); do curl -s -m 2 "http://127.0.0.1:$PORT/api/health" >/dev/null && break; sleep 1; done
  local session
  session=$(api POST /api/auth/register "{\"name\":\"E2E\",\"email\":\"$E2E_EMAIL\",\"password\":\"$E2E_PASSWORD\"}")
  TOKEN=$(printf '%s' "$session" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -z "$TOKEN" ]; then
    session=$(api POST /api/auth/login "{\"email\":\"$E2E_EMAIL\",\"password\":\"$E2E_PASSWORD\"}")
    TOKEN=$(printf '%s' "$session" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  fi
  [ -n "$TOKEN" ] || { echo "Could not sign in: $session"; exit 1; }
  if [ -z "$(sql "select id from monitored_applications where name='test-target'")" ]; then
    api POST /api/applications '{"name":"test-target","url":"http://test-target.demo.svc.cluster.local:3000/health","framework":"Node.js","environment":"Development","dockerContainer":"k8s:demo/test-target"}' >/dev/null
  fi
  APP_ID=$(sql "select id from monitored_applications where name='test-target'")
  [ -n "$APP_ID" ] || { echo "Could not register the application"; exit 1; }
  # RELAY starts every policy at require_approval or disabled. The test opts in, as an operator would.
  sql "update recovery_policies set mode='allow_automatically' where id in ('policy-restart','policy-kubernetes-rollback','policy-kubernetes-scale','policy-kubernetes-delete-pod')" >/dev/null
}

deployment_settled() {
  local want ready updated
  want=$(kubectl -n "$DEMO" get deploy test-target -o 'jsonpath={.spec.replicas}')
  ready=$(kubectl -n "$DEMO" get deploy test-target -o 'jsonpath={.status.readyReplicas}')
  updated=$(kubectl -n "$DEMO" get deploy test-target -o 'jsonpath={.status.updatedReplicas}')
  [ "$want" -gt 0 ] && [ "${ready:-0}" = "$want" ] && [ "${updated:-0}" = "$want" ]
}

wait_settled() {
  for _ in $(seq 1 90); do
    deployment_settled && [ -z "$(sql "select id from incidents where application_id='$APP_ID' and state <> 'recovered' limit 1")" ] && return 0
    sleep 2
  done
  return 1
}

# A scenario passes when an incident opened after the failure was injected, a recovery with the expected action
# reached the "verified" phase, and the workload is fully ready again from outside.
run_scenario() {
  local name=$1 expected=$2 inject=$3 replicas=${4:-1}
  step "--- $name"
  kubectl -n "$DEMO" scale deploy/test-target --replicas="$replicas" >/dev/null
  wait_settled || { step "Did not settle before $name"; }
  local since start outage_start="" outage_end="" outage="none" action phase detect recover
  since=$(sql "select now()")
  start=$(date +%s%3N)
  eval "$inject"
  local deadline=$(( $(date +%s) + TIMEOUT )) healed=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if target_healthy; then
      [ -n "$outage_start" ] && [ -z "$outage_end" ] && outage_end=$(date +%s%3N)
    else
      [ -z "$outage_start" ] && outage_start=$(date +%s%3N)
      outage_end=""
    fi
    phase=$(sql "select a.phase from recovery_actions a join incidents i on i.id=a.incident_id where i.application_id='$APP_ID' and a.intended_at > '$since' and a.action='$expected' order by a.intended_at desc limit 1")
    if [ "$phase" = "verified" ] && deployment_settled && target_healthy; then healed=1; break; fi
    sleep 1
  done
  local end
  end=$(date +%s%3N)
  [ -n "$outage_start" ] && outage=$(awk "BEGIN{printf \"%.1f s\", (${outage_end:-$end}-$outage_start)/1000}")
  action=$(sql "select string_agg(a.action||':'||a.phase, ', ' order by a.attempt) from recovery_actions a join incidents i on i.id=a.incident_id where i.application_id='$APP_ID' and a.intended_at > '$since'")
  detect=$(sql "select round(extract(epoch from (min(i.detected_at)-'$since'::timestamptz))::numeric,1) from incidents i where i.application_id='$APP_ID' and i.detected_at > '$since'")
  recover=$(awk "BEGIN{printf \"%.1f\", ($end-$start)/1000}")
  if [ "$healed" = 1 ]; then result=healed; else result=NOT-HEALED; failed=$((failed+1)); kubectl -n "$DEMO" get pods; fi
  step "$name: $result detect=${detect:-?}s outage=${outage} healed_after=${recover}s actions=[${action:-none}]"
  echo "| $name | $expected | ${detect:-?} s | ${outage} | ${recover} s | ${action:-none} | $result |" >> "$RESULTS"
}

inject_pod() { kubectl -n "$DEMO" exec "$1" -- node -e "fetch('http://127.0.0.1:3000/chaos/pool-exhausted',{method:'POST'}).then(()=>process.exit(0))"; }

first_pod() { kubectl -n "$DEMO" get pods -l app=test-target --field-selector=status.phase=Running -o 'jsonpath={.items[0].metadata.name}'; }

command -v kind >/dev/null && command -v helm >/dev/null && command -v kubectl >/dev/null || { echo "kind, helm and kubectl must be on PATH"; exit 1; }
[ "${SKIP_SETUP:-}" = "1" ] || setup
kubectl config use-context "kind-$CLUSTER" >/dev/null || exit 1
register

mkdir -p "$(dirname "$RESULTS")"
{
  echo "# Kubernetes end-to-end results"
  echo
  echo "Date: $(date -u +%Y-%m-%dT%H:%MZ). Kubernetes $(kubectl version -o json 2>/dev/null | sed -n 's/.*"gitVersion": "\(v[^"]*\)".*/\1/p' | tail -1), kind, 1 node. Monitoring interval 5 s, signal interval 5 s."
  echo
  echo "| Failure | Expected action | Detected after | Outage seen from outside | Healed after | Recovery actions | Result |"
  echo "|---|---|---|---|---|---|---|"
} > "$RESULTS"

run_scenario "Scaled to zero" SCALE_WORKLOAD 'kubectl -n demo scale deploy/test-target --replicas=0 >/dev/null'
run_scenario "Bad image rollout" ROLLOUT_UNDO 'kubectl -n demo set image deploy/test-target app=relay-aegis-test-target:does-not-exist >/dev/null'
run_scenario "Crashing rollout" ROLLOUT_UNDO "kubectl -n demo patch deploy test-target --type=json -p '[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/command\",\"value\":[\"node\",\"-e\",\"console.error(\\\"FATAL boom\\\");process.exit(1)\"]}]' >/dev/null"
run_scenario "All pods stuck" RESTART_CONTAINER 'inject_pod "$(first_pod)"'
run_scenario "One bad pod of three" DELETE_POD 'inject_pod "$(first_pod)"' 3
kubectl -n "$DEMO" scale deploy/test-target --replicas=1 >/dev/null

echo
cat "$RESULTS"
echo
if [ "$failed" -gt 0 ]; then
  step "$failed scenario(s) did not heal. RELAY logs:"
  kubectl -n "$NS" logs "deploy/$RELEASE-backend" --tail=60
  exit 1
fi
step "All scenarios healed. Results: $RESULTS"
