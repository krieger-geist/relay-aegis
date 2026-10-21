# RELAY AEGIS Backend

Java 21 + Spring Boot 3.4 REST API. Docker Compose runs it with PostgreSQL 16 and Flyway migrations (`src/main/resources/db/migration`, currently V1–V9). The direct developer profile uses H2 and leaves Docker recovery disabled unless explicitly enabled.

## Run

```powershell
mvn spring-boot:run          # http://localhost:8080/api/health
mvn -B clean test            # full test suite
```

All endpoints except `/api/health`, `/actuator/health` and `/api/auth/*` require a bearer access token. The complete endpoint list is in [`../openapi.yaml`](../openapi.yaml).

## Packages

| Package | Responsibility |
|---|---|
| `auth` | JWT access tokens, rotating refresh cookies, roles, login attempt limiting |
| `diagnostics` | Deterministic failure patterns and ranked hypotheses |
| `recovery` | Recovery policy, guard (attempts, cooldown, circuit breaker), executor, verification |
| `runtime` | Read-only Docker inspection of allowlisted containers |
| `assessment` | GitHub repository analyzer and live URL analyzer |
| `findings` | Canonical findings, ranking, actionability, evidence correlation |
| `remediation` | Patch strategies, sandbox validation, GitHub pull-request workflow |
| `repair` | Unified Auto-Repair orchestration (`RepairStrategy`, `RepairOrchestrator`) |
| `ai` | Optional Gemini hypotheses with evidence sanitizing |

Configuration is documented in the root [README](../README.md) and [`.env.example`](../.env.example).
