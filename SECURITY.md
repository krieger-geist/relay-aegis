# Security Policy

RELAY AEGIS can act on Docker containers and open pull requests, so security reports are taken seriously.

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No |

## Reporting a vulnerability

Please **do not open a public issue** for a vulnerability.

Report it privately through GitHub: **Security → Report a vulnerability** on
[krieger-geist/relay-aegis](https://github.com/krieger-geist/relay-aegis/security/advisories/new).

Include what you can of:
- the affected component (backend, frontend, recovery, sandbox, GitHub remediation, Live URL) and version or commit;
- steps to reproduce, and the impact you observed or expect;
- any suggested fix.

You can expect an acknowledgement within 3 working days and an assessment within 10. Fixes are released as a patch
version and credited in the changelog unless you prefer otherwise.

## Scope and design boundaries

These are deliberate limits; reports that bypass them are especially valuable:

- Recovery acts only on allowlisted containers, through a closed set of actions, after policy and guard checks.
  Recovery against systems marked Production is blocked.
- Code repair never pushes to the default branch, never force-pushes and never merges; only sandbox-validated patches
  can be applied, by an authorized approver, to allowlisted repositories.
- Repository analysis never executes repository code outside the disposable sandbox.
- Live URL investigation refuses private, loopback, link-local, CGNAT and unique-local addresses before every request
  and does not follow redirects.
- AI output is advisory only and never authorizes or executes an action.

Known limitation: the Compose stack mounts the Docker socket and is intended for a local, single host. Do not expose it
to untrusted networks.
