## Coding Style & Naming Conventions

- Java 17, Spring Boot 3.2.5, Maven wrapper (`mvnw.cmd`).
- Package base: `com.mnemoscape.{module}`.
- Four-character indentation, UTF-8 encoding.
- Class names: PascalCase. Methods/variables: camelCase. Constants: UPPER_SNAKE_CASE.
- JPA entities under `model/entity`, DTOs under `model/dto`, repositories under `repository`.
- Common shared infrastructure lives in the `common` module: `exception`, `security`, `ratelimit`, `idempotency`, `event`, `admin/cache`.

## Build, Test, and Development Commands

| Command | Purpose |
|---|---|
| `.\mvnw.cmd clean install -DskipTests` | Full build (skip tests). Use `-pl {module} -am` for a single module. |
| `.\mvnw.cmd test -pl {module}` | Run tests for one module. |
| `.\mvnw.cmd spring-boot:run -pl {module}` | Start a single service (IDE alternative). |
| `.\Restart-MnemoscapeServices.ps1` | Kill old processes, source `.env.workpc`, start all 6 jars. |
| `.\rebuild-memory.ps1` | Kill port 8082, rebuild `memory-service`, start jar, wait for port UP. |

**Important**: Set `$env:MAVEN_OPTS = "-Xmx4g"` before building — `memory-service` will OOM otherwise. Windows locks running jars; kill the owning process before rebuild.

## Project Structure & Module Organization

```
backend/
  common/            Shared infrastructure (exceptions, JWT, rate-limit, idempotency, events)
  api-gateway/       Spring Cloud Gateway (:8080)
  auth-service/      Login, registration, JWT, users, friends (:8081)
  memory-service/    Memory CRUD, Neo4j graph, event publishing (:8082)
  ai-service/        LLM, entity extraction, Milvus vectors (:8083)
  resonance-service/ Drift bottles, chat, achievements, WebSocket (:8084)
  asset-service/     File upload, MinIO, asset WebSocket (:8085)
  scripts/           PowerShell helpers for build, deploy, restart
```

Each service follows a layered structure: `controller` → `service` → `repository`, with `model/entity` and `model/dto`.

## Testing Guidelines

- Framework: JUnit 5 + Mockito + Spring Boot Test.
- Test classes mirror the main source tree under `src/test/java`.
- Use `@WebMvcTest` for controller-layer tests and `@DataJpaTest` for repository tests.
- Integration tests that need real middleware should be tagged `@Tag("integration")`.

## Commit & Pull Request Guidelines

- Follow the existing convention: `type: short description` (e.g. `feat:`, `fix:`, `chore:`, `release:`, `backup:`).
- Keep commits focused — one logical change per commit.
- PRs should include a brief description of what changed and why, linked issues, and any manual verification steps.

## Configuration & Environment

- Environment variables are loaded from `backend/.env.workpc` before startup.
- Nacos (discovery) at `${NACOS_HOST:100.66.166.46}:8848`. Config pull is off by default.
- MySQL, Redis, RabbitMQ, Neo4j, and Milvus addresses all use `${VAR:default}` placeholders in `application.yml`.
- JWT secret and token expirations are set in `application.yml` under `mnemoscape.jwt.*`.

## Key Architecture Patterns

- **Fail-open degradation**: Rate-limit and idempotency guards let requests through when Redis is down.
- **Outbox pattern**: `memory-service` persists events when RabbitMQ is unreachable; a scheduled worker retries every 30 s.
- **Circuit breaker**: `Resilience4j` protects `memory-service → ai-service` Feign calls (50% failure → 30 s open).
- **Dual cache**: Caffeine (in-process, fast) for per-memory lookups; Redis (shared) for admin aggregation dashboards.
- **Stateless auth**: JWT via `Authorization: Bearer` header; `api-gateway` is the primary auth boundary.
