# Integrations

RELAY can take alerts from the tools you already run, tell people what it is doing, and ask for approval in Slack.
Everything here is off until you set its variable in `.env`; then recreate the backend
(`docker compose up -d backend`).

| You want | Set | Endpoint |
|---|---|---|
| Alerts from Prometheus Alertmanager | `RELAY_SIGNALS_TOKEN` | `POST /api/signals/alertmanager` |
| Alerts from Sentry | `RELAY_SENTRY_CLIENT_SECRET`, `RELAY_SENTRY_PROJECTS` | `POST /api/signals/sentry` |
| Alerts from anything else | `RELAY_SIGNALS_TOKEN` | `POST /api/signals/webhook` |
| Slack messages | `RELAY_SLACK_WEBHOOK_URL` | (outbound) |
| Approve / Reject buttons in Slack | `RELAY_SLACK_SIGNING_SECRET`, `RELAY_SLACK_APPROVERS` | `POST /api/slack/interactions` |
| Every notification as signed JSON | `RELAY_NOTIFY_WEBHOOK_URL`, `RELAY_NOTIFY_WEBHOOK_SECRET` | (outbound) |
| Links back to RELAY in messages | `RELAY_PUBLIC_URL` | |

An endpoint whose credential is not set answers `404`. A wrong credential answers `401`.

## How an alert becomes an incident

1. The alert names an application. RELAY looks it up by id, name (case-insensitive) or Docker container name.
   `?application=<id|name|container>` on the URL overrides whatever the alert says.
2. A **firing** alert opens an incident for that application, or is added as evidence to the one already open. The
   normal pipeline then runs: evidence, diagnosis (including YAML runbooks), recovery policy, guard, journal,
   verification. An alert never runs an action by itself.
3. Repeat deliveries of the same alert are recorded once; a still-firing alert is noted again at most once an hour.
4. A **resolved** alert is only recorded. RELAY closes the incident when its own checks see the application healthy
   **and** no alert on it is still firing. An alert that never sends "resolved" stops holding the incident after
   6 hours.

Each response lists one result per alert:

| Result | Meaning |
|---|---|
| `opened` | A new incident was opened |
| `attached` | Added as evidence to the open incident |
| `resolved_noted` | The open incident records that the alert resolved |
| `duplicate` | Same state as last time; nothing recorded |
| `no_open_incident` | A resolved alert with no open incident; nothing to do |
| `no_application` | The alert named no registered application |
| `ignored` | A Sentry event RELAY does not act on |

`relay_signals_received_total{source, status}` counts every alert received.

## Prometheus Alertmanager

Generate a token of at least 24 characters and set it as `RELAY_SIGNALS_TOKEN`:

```powershell
-join ((48..57) + (97..122) | Get-Random -Count 40 | ForEach-Object { [char]$_ })
```

Add a receiver to `alertmanager.yml`:

```yaml
receivers:
  - name: relay
    webhook_configs:
      - url: http://relay-backend:8080/api/signals/alertmanager
        send_resolved: true          # needed so RELAY knows when the alert stops
        http_config:
          authorization:
            type: Bearer
            credentials: <RELAY_SIGNALS_TOKEN>

route:
  receiver: relay
```

RELAY reads the application from the first of these labels that is present: `relay_application`, `container`,
`container_name`, `name`, `service`, `job`. Add `relay_application` to a rule when none of the others match:

```yaml
- alert: OrdersHighErrorRate
  expr: sum(rate(http_requests_total{job="orders",code=~"5.."}[5m])) / sum(rate(http_requests_total{job="orders"}[5m])) > 0.05
  for: 2m
  labels:
    severity: critical
    relay_application: orders-service
  annotations:
    summary: Orders 5xx rate above 5%
```

The title is `summary` (or `title`, or the alert name), the detail is `description` (or `message`), and the alert's
`fingerprint` keeps repeat deliveries together. Up to 50 alerts per request.

**Grafana alerting** sends the same alert format: add a *Webhook* contact point with URL
`<RELAY>/api/signals/alertmanager` and, under *Optional Webhook settings*, Authorization header scheme `Bearer` with the
token as credentials.

Try it without Alertmanager:

```bash
curl -X POST http://localhost:8080/api/signals/alertmanager -H "Authorization: Bearer $RELAY_SIGNALS_TOKEN" -H "Content-Type: application/json" -d '{"status":"firing","alerts":[{"status":"firing","fingerprint":"demo-1","labels":{"alertname":"HighErrorRate","container":"relay-aegis-test-target","severity":"critical"},"annotations":{"summary":"5xx rate above 5%"}}]}'
```

## Sentry

1. In Sentry: **Settings → Developer Settings → Custom Integrations → Create New Integration → Internal Integration**.
2. **Webhook URL:** `https://<your RELAY>/api/signals/sentry`. Sentry must be able to reach it.
3. Turn on **Alert Rule Action**, and under **Webhooks** subscribe to **issue**. Give it **Read** on Issue & Event.
4. Save, then copy the integration's **Client Secret** into `RELAY_SENTRY_CLIENT_SECRET`. RELAY checks every request's
   `Sentry-Hook-Signature` (HMAC-SHA256 of the body) against it.
5. Map Sentry project slugs to RELAY applications when their names differ:
   `RELAY_SENTRY_PROJECTS=orders-api=orders-service,web=storefront`. An unmapped slug is used as the application name.
6. To send alert rules to RELAY, add the integration as an action on an issue or metric alert rule.

What RELAY uses:

| Sentry sends | RELAY treats it as |
|---|---|
| `issue` `created` / `unresolved` | firing |
| `issue` `resolved` | resolved |
| `event_alert` (an issue alert rule fired) | firing |
| `metric_alert` `critical` / `warning` | firing |
| `metric_alert` `resolved` | resolved |
| anything else (`assigned`, `ignored`, installation events) | ignored |

## Any other tool: generic webhook

Send `Authorization: Bearer <RELAY_SIGNALS_TOKEN>` and a JSON body. Only `title` is required.

```json
{
  "application": "orders-service",
  "title": "Checkout journey failing",
  "status": "firing",
  "detail": "3 of 3 synthetic runs failed at the payment step",
  "severity": "critical",
  "fingerprint": "synthetic-checkout",
  "link": "https://monitor.example.com/checks/42",
  "startsAt": "2026-11-04T10:00:00Z"
}
```

`status` is `firing` (default) or `resolved`. Send the same `fingerprint` with `"status": "resolved"` when it clears;
without a fingerprint the title is used.

## Slack

### Messages

1. Create a Slack app at <https://api.slack.com/apps> (**From scratch**) in your workspace.
2. **Incoming Webhooks → On → Add New Webhook to Workspace**, pick the channel, and copy the URL into
   `RELAY_SLACK_WEBHOOK_URL`. It must start with `https://hooks.slack.com/`.
3. Set `RELAY_PUBLIC_URL` to the address people open RELAY at, so each message links to its incident.

RELAY then posts when an incident opens, when recovery needs approval, and when recovery is verified or not.

### Approve / Reject buttons

1. In the same app: **Interactivity & Shortcuts → On**, **Request URL** `https://<your RELAY>/api/slack/interactions`.
   Slack must be able to reach it over HTTPS (for a local try, a tunnel such as `cloudflared` or `ngrok`).
2. **Basic Information → App Credentials → Signing Secret** into `RELAY_SLACK_SIGNING_SECRET`.
3. Put the Slack member ids of the people allowed to approve in `RELAY_SLACK_APPROVERS`, comma-separated
   (`U012AB3CD,U045EF6GH`). Find an id in Slack under the person's profile → **⋮ → Copy member ID**.

With both set, an approval-required message gets **Approve `<ACTION>`** (with a confirmation naming the action and its
target) and **Reject** buttons.

- **Approve** runs the same operator recovery as *Run recovery* in the UI. The recovery policy, guard, journal and
  verification still apply, so an approval cannot run a blocked action.
- **Reject** runs nothing. Either way the incident records who decided, as approval evidence.
- Someone not on the list gets a private "not on RELAY's approver list" reply and nothing changes.
- Every click must carry a valid `X-Slack-Signature` made with the signing secret over the raw body, with a timestamp
  less than five minutes old; anything else is refused with `401`.
- Production incidents never get buttons.
- Text from alerts is escaped, so an alert cannot mention `@channel` or add its own links.

With several backend instances, any of them can receive the click; it is forwarded to the leader with the Slack
headers intact.

## Outbound webhook

`RELAY_NOTIFY_WEBHOOK_URL` receives every notification as JSON:

```json
{
  "event": "recovery.approval_required",
  "incidentId": "inc-1a2b3c",
  "application": "Orders",
  "environment": "Development",
  "severity": "Sev-1",
  "title": "Orders returning 503",
  "summary": "Connection pool exhausted",
  "action": "RESTART_CONTAINER",
  "target": "orders",
  "detail": "Policy requires approval.",
  "link": "https://relay.example.com/incidents/inc-1a2b3c",
  "at": "2026-11-04T10:00:05Z"
}
```

Events (also in the `X-Relay-Event` header): `incident.opened`, `recovery.approval_required`, `recovery.verified`,
`recovery.not_verified`.

With `RELAY_NOTIFY_WEBHOOK_SECRET` set, each request also carries `X-Relay-Timestamp` and
`X-Relay-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<body>">`. Verify it before trusting the body:

```python
import hashlib, hmac, time

def verify(secret: str, headers, body: bytes) -> bool:
    ts = headers["X-Relay-Timestamp"]
    if abs(time.time() - int(ts)) > 300:
        return False
    expected = "sha256=" + hmac.new(secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, headers["X-Relay-Signature"])
```

## Delivery

Notifications are sent from one background queue (at most 200 waiting), after the incident change is committed, so a
slow or failing receiver never delays detection or recovery. A failed delivery is logged and not retried. RELAY never
follows redirects when it calls out.
