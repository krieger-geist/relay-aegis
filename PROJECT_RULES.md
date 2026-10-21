# RELAY AEGIS Project Rules

1. Treat backend records and measured runtime observations as the source of truth. Do not invent sample incidents, applications, metrics, or successful recoveries.
2. Do not add demo scenario selectors, failure-injection controls, mock REST data, or persistent demo banners to the live application UI.
3. The local `test-target` container is an isolated health endpoint for validating monitoring and policy-controlled Docker recovery; it does not seed UI records or provide a scenario switch.
4. Distinguish observed evidence, AI hypotheses, policy decisions, and verified outcomes in UI and API semantics.
5. Only `verification.status === "passed"` may render “Recovery verified”. A timeout, missing result, or unknown result is not success.
6. API provenance must reflect source reliability: `live`, `historical`, `estimated`, or `unavailable`. Do not emit `demo` for new records. The V3 migration cleans up prior demo-provenance records once.
7. AI diagnosis is advisory and must never independently authorize or execute a recovery action.
8. Assessment triage sends finding metadata only. Incident diagnosis may send recorded incident context/evidence to Gemini; do not transmit secrets or sensitive personal data.
9. GitHub repository scanning is read-only, shallow, resource-bounded, and does not execute repository code. Public repository scanning is not permission to modify that repository.
10. Live URL assessment and monitoring require explicit hostname allowlists and target authorization. Do not follow redirects or probe private networks through user-supplied URLs.
11. No production recovery is permitted in this release. Recovery must remain allowlist- and policy-controlled; enabling automatic recovery requires typed `CONFIRM-AUTO-REPAIR`.
12. Do not claim code repair, passing tests, external email delivery, or a successful recovery unless those operations actually ran and were verified.
13. Typography: Space Grotesk (`--font-display`) for headings and key numbers, IBM Plex Sans (`--font-sans`) for UI text, IBM Plex Mono (`--font-mono`) for identifiers, logs and code. Use CSS tokens rather than hard-coded colors in React components.
14. Keep API keys in backend environment configuration only; never bundle them into `VITE_` variables or commit `.env`.
15. Code remediation never pushes to the default branch, never force-pushes and never merges. Only sandbox-validated patches can be applied, only by an authorized approver, only to allowlisted repositories, and only as a reviewed pull request.
16. A repair is "Resolved" only after verification passes. An executed action, a created pull request or an AI statement is never proof of resolution.
