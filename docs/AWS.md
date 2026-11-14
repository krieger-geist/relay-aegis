# Running RELAY on one AWS server

This puts the released RELAY on a single small EC2 server with a public HTTPS address. It suits a demo or a small
team, not production: one server, one database, no backups beyond the disk.

What runs on the server: PostgreSQL, the RELAY backend and frontend (the signed release images), the demo application
RELAY watches and heals, the two Docker proxies, and [Caddy](https://caddyserver.com/), which fetches a free Let's
Encrypt certificate. Files: [`deploy/aws`](../deploy/aws).

## Cost

| Item | Size | About, per month (Mumbai, ap-south-1) |
|---|---|---|
| EC2 `t3.small` | 2 vCPU, 2 GB memory | $16.35 ($0.0224 an hour) |
| EBS gp3 disk | 20 GB | $1.80 |
| Public IPv4 address | 1 | $3.65 |
| **Total** | | **about $22** |

Prices change; check them in the [AWS Pricing Calculator](https://calculator.aws/). A stopped server costs only the
disk and the address, about $5 a month.

On a new AWS account on the free plan, credits pay for this and the card is not charged. The free plan ends six months
after the account was created or when the credits run out, whichever comes first. `t3.small` is on the free plan's
list of eligible types for accounts created after 15 July 2025.

## 1. Create the server (AWS console)

1. Choose the region **Asia Pacific (Mumbai) ap-south-1** at the top right.
2. EC2, then **Launch instance**:
   - Name: `relay-aegis`
   - Image: **Ubuntu Server 24.04 LTS**, 64-bit (x86). The release images are x86 only, so not a `t4g` server.
   - Instance type: **t3.small**
   - Key pair: create one (`relay-aegis`, ED25519, `.pem`) and keep the file safe. Without it you cannot log in.
   - Network settings, Edit, security group `relay-aegis`:
     - SSH, port 22, source **My IP**
     - HTTP, port 80, source Anywhere (Caddy needs it to obtain the certificate)
     - HTTPS, port 443, source Anywhere
   - Storage: **20 GiB gp3**
   - Advanced details: leave **IAM instance profile** empty. Set **Metadata version** to *V2 only* and **Metadata
     response hop limit** to **1**, so containers cannot read the server's metadata.
3. **Launch instance**, then copy its **Public IPv4 address**.

Optional, so the address survives a stop and start: EC2, **Elastic IPs**, Allocate, then Associate it with the
server. It costs the same as the address that comes with the server. Without it, the address and the HTTPS name change
after every stop, and `setup.sh` has to be run again with the new name.

## 2. Install RELAY

From the repository folder on your PC (PowerShell or Git Bash), copy the files and log in:

```bash
scp -i relay-aegis.pem -r deploy/aws ubuntu@<public-ip>:~/
ssh -i relay-aegis.pem ubuntu@<public-ip>
```

On the server:

```bash
sudo bash ~/aws/setup.sh --admin-email you@example.com
```

It installs Docker, adds a 2 GB swap file, downloads RELAY v1.5.0 into `/opt/relay-aegis`, writes `/opt/relay-aegis/.env`
with a random database password and token secret, and starts everything. After 1 to 3 minutes it prints the address,
for example `https://13-233-10-20.sslip.io`. [sslip.io](https://sslip.io) turns the IP address into a name, so no
domain purchase is needed. With your own domain, point an A record at the server and pass `--domain relay.example.com`.

Open the address and **sign up with the admin email** first: that account gets the admin role.

Then add the demo application: name `test-target`, URL `http://test-target:3000/health`, Container
`relay-aegis-test-target`. Recovery policies start at `require_approval`, so the first fix waits for your approval in
the incident; an admin can switch a policy to `allow_automatically`.

To break it, on the server (either one):

```bash
docker stop relay-aegis-test-target      # the application goes down
docker exec relay-aegis-test-target node -e "fetch('http://127.0.0.1:3000/chaos/pool-exhausted',{method:'POST'})"   # it stays up but reports DOWN
```

RELAY detects the failure on its next checks (every 60 seconds by default), opens an incident and restarts the container.

## Everyday commands (on the server)

```bash
cd /opt/relay-aegis
alias relay='docker compose -f docker-compose.yml -f deploy/aws/docker-compose.aws.yml'
relay ps                          # what is running
relay logs --tail=100 backend     # backend logs
relay restart backend
sudo bash deploy/aws/setup.sh --version 1.5.0    # move to a newer release; keeps .env and the data
```

To pause spending, stop the server in the EC2 console (**Instance state**, **Stop**). Everything starts again by itself
when you start it. To end it completely, **Terminate** the server and release the Elastic IP if you made one; this
deletes RELAY's data.

## Limits and safety

- Anyone with the address can create an account. Ordinary accounts are users, not admins, and RELAY on this server can
  watch and restart only the demo application, because monitoring is limited to `test-target` and recovery to the
  container `relay-aegis-test-target`. Do not add real applications to a public demo.
- Only admins can change recovery policies or reload runbooks, so a visitor cannot switch off healing for everyone.
  Each ordinary account may start 10 assessments and add 3 applications an hour (`RELAY_LIMIT_*` in the compose file).
- Only ports 80 and 443 are public. PostgreSQL and the backend listen on the server itself only.
- No backups. The database lives in a Docker volume on the 20 GB disk; an EBS snapshot is the simplest copy.
- 2 GB of memory is enough for this stack with the swap file. The monitoring profile (Prometheus and Grafana) and the
  high-availability profile need a larger server.
- Set an alert in **Billing and Cost Management**, **Budgets**, for example at $10 a month, to see credits draining.
