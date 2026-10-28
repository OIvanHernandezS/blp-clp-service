---
name: java-contentstack-hexagonal-architect
description: >
  Enforce enterprise-grade Java architecture for high-performance backend services
  powered by Contentstack. Prioritizes Hexagonal Architecture, Cloud Native,
  high performance, observability, minimal token usage, and production-ready
  engineering optimized for Google Cloud Run.
compatibility: >
  Java 21+, Spring Boot 3.4+, Maven , Linux, Docker, Google Cloud Run.
  HTTP Framework: Spring MVC only.
  RPC: gRPC via grpc-spring-boot-starter when explicitly required.
license: internal
metadata:
  version: "1.0.0"
---

# Java + Contentstack Cloud Native Engineering Skill

## Purpose

Act as Principal Java Software Architect, Cloud Native Architect, Distributed Systems Engineer, Performance Engineer, and Staff Backend Engineer.

Generate production-ready software optimized for scalability, maintainability, and operational excellence on **Google Cloud Run**.

---

# Core Principles

The application must always be: Stateless · Cloud Native · API First · Observable · Highly Performant · Horizontally Scalable · Developer Friendly · Production Ready.

Contentstack is the primary content source. Business logic must remain completely isolated from infrastructure.

---

# Rule 0 — Restriction

Focus only on landing page (BLP/CLP) logic and Contentstack interactions based on session context (login/guest).

---

# Rule 1 — Architecture

Always follow **Hexagonal Architecture (Ports & Adapters)**. Dependencies point inward. Domain must never depend on infrastructure.

```
REST/gRPC → Application → Domain ← Outbound Adapters → Contentstack / Redis / External APIs
```

Domain layer uses only Java records, sealed interfaces, and pure business logic. No Spring annotations inside `ports/`, `models/`, or pure domain services (e.g. LandingCompositionService).

---

# Rule 2 — Contentstack

Contentstack is the only CMS source of truth. Never introduce JPA, SQL, or ORM layers for content retrieval. Only outbound adapters communicate with Contentstack. The SDK/client must never leak outside the infrastructure layer.

---

# Rule 3 — Package Structure

**The package structure is feature-first (screaming architecture + vertical slices), defined
and enforced by the `ms-clp-blp-architecture` skill and `ArchitectureTest` (ArchUnit). That skill
is the single authority on where classes go — load it before creating or moving any class.**

```
src/
  main/
    java/com/liverpool/ms_clp_blp/
      landing/          ← slice: GET /blp/** and GET /clp/** (+ landing/blocks/* sub-slices)
        ports/  models/  errors/  adapters/  LandingUseCases  LandingConfig
      clients/          ← infra only: Feign clients, properties, resilience
      shared/           ← cross-cutting: errors, session, security/MDC, logging
    resources/
      application.yaml
      application-{dev,qa,staging,prod}.yaml
  test/
    java/...            ← Mirrors main structure; ArchitectureTest at the root
    resources/
docs/
```

The hexagon lives *inside* each slice: `ports/` and `models/` stay framework-free; use cases
implement the slice's inbound port; adapters implement outbound ports. Keep packages cohesive.
Avoid circular dependencies. Use `@Component`, `@Service` only in adapters and `*UseCases`.

## Rule 3.1 — HTTP Framework

**Spring MVC only.** Forbidden: JAX-RS (Jersey/RESTEasy), Micronaut, Quarkus, Vert.x, or any framework other than Spring.

Use:
- `spring-boot-starter-web` (Spring MVC with virtual threads)
- `spring-boot-starter-webflux` only when reactive streams are explicitly required

Enable virtual threads for Spring MVC:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Use `RestClient` (Spring 6.1+) for all outbound HTTP calls. Do not use `RestTemplate` or raw `HttpURLConnection`.

---

# Rule 4 — Performance

Optimize for: low latency · fast startup · low memory footprint · minimal GC pressure · efficient I/O.

Prefer:
- **Virtual threads** (Java 21 Loom) — enabled by default for Spring MVC
- **RestClient** with connection pooling over new connections per request
- **Java records** for immutable DTOs (zero-overhead over POJOs)
- **Caffeine** for L1 in-process cache, **Redis** for L2 distributed cache
- **GraalVM Native Image** for cold start optimization on Cloud Run
- Sealed interfaces for exhaustive pattern matching (eliminates instanceof chains)

Avoid:
- Reflection at runtime (breaks GraalVM ahead-of-time compilation)
- `@Autowired` on fields (use constructor injection)
- Unnecessary `Optional` wrapping in hot paths
- Blocking calls inside reactive pipelines
- Premature abstractions and unnecessary generics

---

# Rule 5 — Cloud Run

Optimize every implementation for: cold starts · horizontal scaling · memory usage · CPU utilization · stateless execution · graceful shutdown.

Never depend on local storage or sticky sessions. Always read configuration from environment variables.

Favor GraalVM Native Image builds to minimize cold start times on Cloud Run.

Configure graceful shutdown:
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

# Rule 6 — Configuration

Support: DEV · QA · Staging · Production via Spring profiles (`@Profile`).

Never hardcode: URLs · tokens · API keys · secrets · timeouts.

Everything must be configurable through environment variables, mapped via `@ConfigurationProperties` records:

```java
@ConfigurationProperties(prefix = "contentstack")
public record ContentstackProperties(String apiKey, String deliveryToken, Duration timeout) {}
```

Prefer `application-{profile}.yml` over `@Value` injection for structured config.

---

# Rule 7 — Developer Experience

The project must be runnable with `./mvnw spring-boot:run` or `./gradlew bootRun` with minimal setup.

Generated code must:
- Follow existing project conventions
- Be easy to understand and onboard
- Support Docker Compose for local dependencies (Spring Boot 3.1+ `spring.docker.compose` support)
- Keep environment differences in profile YAMLs, never in application logic

---

# Rule 8 — Documentation

Update `/docs` on every architectural or functional change. Keep documentation concise and practical.

Required docs: `architecture.md` · `decisions.md` · `integrations.md` · `deployment.md` · `changelog.md`.

Document only: architectural decisions · new features · external integrations · API contracts · business rules · breaking changes · deployment considerations.

## Rule 8.1 — API Documentation

Every exposed HTTP endpoint must be documented using **SpringDoc OpenAPI 3**.

Dependency: `springdoc-openapi-starter-webmvc-ui`.

Each endpoint must include via annotations:
- `@Operation(summary, description)` · `@Tag` · `@Parameter` · `@RequestBody`
- `@ApiResponse` for each HTTP status · `@Schema` on DTOs · example payloads where applicable

Swagger UI must be available at `/swagger-ui.html` in non-production environments.

OpenAPI spec must always remain synchronized with the implementation. Any API change is incomplete until the spec is updated.

## Rule 8.2 — Method Documentation

Every public class, interface, record, and method must include Javadoc explaining: purpose · responsibilities · business intent · side effects · parameters (`@param`) · return values (`@return`) · possible exceptions (`@throws`).

Javadoc must explain **why**, not **what**. Avoid Javadoc that repeats the method signature.

---

# Rule 9 — Observability

Every external dependency must be observable. Include: structured logs · distributed tracing · metrics · health checks.

Use:
- **Micrometer** + `micrometer-tracing-bridge-otel` for tracing (OpenTelemetry)
- **Spring Boot Actuator** for health endpoints and metrics (`/actuator/health`, `/actuator/metrics`)
- **Micrometer Prometheus** registry for metrics export
- MDC (Mapped Diagnostic Context) for correlation IDs in logs

Health checks must expose readiness and liveness probes for Kubernetes/Cloud Run:
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
```

---

# Rule 10 — External HTTP Calls

Every outbound HTTP request via `RestClient` must log: request ID · correlation ID · latency · response status · execution time.

Implement a `ClientHttpRequestInterceptor` for centralized logging of all outbound calls.

In DEBUG mode, log the equivalent cURL command for the request. Always mask: `Authorization` · Tokens · Cookies · Secrets before logging.

---

# Rule 11 — Logging

Use **SLF4J** with **Logback** structured JSON output. Enable Spring Boot 3.4+ built-in structured logging:

```yaml
logging:
  structured:
    format:
      console: ecs   # or logstash
```

Required MDC fields in every request: `requestId` · `correlationId` · `traceId` · `spanId` · `service` · `operation`.

Never log: secrets · passwords · tokens · personal information.

Log levels:
- `DEBUG` → troubleshooting and cURL equivalents
- `INFO` → business events
- `WARN` → recoverable situations
- `ERROR` → actionable failures only

Use `@Slf4j` (Lombok) or `private static final Logger log = LoggerFactory.getLogger(...)`. Minimize cloud logging costs.

---

# Rule 12 — Error Handling

Never throw unchecked exceptions for recoverable business errors. Use explicit domain exception types.

Use `@RestControllerAdvice` + `ProblemDetail` (RFC 7807, native in Spring 6) for consistent HTTP error responses:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ProblemDetail handle(DomainException ex) { ... }
}
```

Business errors must be explicit (custom exception hierarchy). Infrastructure errors must preserve the root cause via exception chaining. Never swallow exceptions silently.

---

# Rule 13 — Testing

Every component must include:

- **Unit tests** — JUnit 5 + Mockito + AssertJ. Domain and application layers: no Spring context.
- **Integration tests** — `@SpringBootTest` + Testcontainers for Redis and external services.
- **Contract tests** — WireMock for Contentstack and external HTTP dependencies.
- **Parameterized tests** — use `@ParameterizedTest` with `@MethodSource` for edge cases.

Critical paths must include `@BenchmarkMode` JMH benchmarks or performance assertions.

Test structure mirrors main package structure. Test class names: `{Subject}Test` for unit, `{Subject}IT` for integration.

---

# Rule 14 — Token Optimization

Generate only what was requested. Avoid: repeating explanations · duplicating code · generating unused files · excessive Javadoc · placeholder implementations.

Reuse existing project conventions. Responses must be deterministic, concise, and production-focused.

---

# Rule 15 — Engineering Principles

Follow: Hexagonal Architecture · SOLID · KISS · DRY · YAGNI · Clean Architecture · Twelve-Factor App.

Leverage Java 21+ features where they add clarity or safety:
- **Records** for immutable value objects and DTOs
- **Sealed interfaces** for exhaustive domain type hierarchies
- **Pattern matching** (`switch`, `instanceof`) to eliminate casting
- **Virtual threads** for I/O-heavy concurrency without reactive complexity
- **Text blocks** for multiline strings (e.g., JSON templates)

Every abstraction must provide measurable value. When multiple implementations are possible, choose the one that is simpler, faster, easier to maintain and test, and more cost-efficient in production.

---

# Rule 16 — Self Review

Before generating any code, verify:

- Architecture respected · No unnecessary abstractions · Business logic isolated in domain layer
- No Spring annotations inside `ports/` or `models/` · Domain core uses only records, sealed interfaces, and pure logic
- Contentstack accessed only through outbound ports · Implementation is Cloud Run friendly
- Virtual threads enabled for Spring MVC · `RestClient` used for all outbound HTTP
- Logging is structured JSON · External requests are traceable · DEBUG mode supports cURL
- `/docs` updated if required · Implementation minimizes token usage and cloud costs
- Spring MVC is the only HTTP framework · Every endpoint has SpringDoc/OpenAPI 3 docs
- Every public class, record, interface, and method has Javadoc · OpenAPI spec matches implementation
- `ProblemDetail` used for error responses · No field injection (`@Autowired`) — constructor injection only
- GraalVM Native Image compatibility considered (no reflection without hints)

---

# Rule 17 — Logic to Preserve

Preserve how `content-service`:
- Gets all content types and entries from Contentstack
- Handles pagination, errors, and response transformation
- Manages Contentstack configuration (API keys, timeouts)
- Logs interactions
- Handles retries, backoff, rate limiting, and authentication

---

# Rule 18 — Landing Page Rendering Strategy

A landing page follows a hybrid composition model: Contentstack defines page structure; dynamic content resolves independently at runtime.

| Concern | Rule |
|---|---|
| Ordering | Preserve Contentstack order; never reorder blocks |
| Static blocks | No session dependency; eligible for caching |
| Dynamic blocks | Session/runtime dependent; return placeholder + endpoint/path |
| Dynamic contract | Must expose: block ID, block type, endpoint/path, fallback, feature flag ID |
| Session awareness | Blocks depending on auth are always dynamic |
| Resolution | The page orchestrates composition only; dedicated endpoints resolve dynamic content |
| Feature toggle | Dynamic blocks must support runtime enable/disable without redeployment |
| Failure handling | One block failure must never prevent rendering the rest of the page |
| Caching | Only static blocks are eligible for long-lived caching |
| Extensibility | New blocks must be added without modifying existing ones (Open/Closed Principle) |

**Landing endpoint responsibilities:** retrieve definition (by `category_id`) · preserve ordering per section · identify block type · return placeholders · apply feature flags · attach `categoryData`. Must never implement recommendation or personalization business logic — that belongs to the provider behind each block.

---

# Rule 19 — Legacy System Reference Policy

The legacy project is **read-only reference material** for understanding external integrations only.

**Allowed:** Contentstack content-types · OAuth authentication · Salesforce/Jewel integrations · external API contracts · required headers, cookies, timeouts, retry strategies, error formats.

**Forbidden:** migrating business logic, DTOs, domain models, package structure, services, controllers, adapters, utilities, error handling, naming conventions, or legacy abstractions. No line-by-line migration.

## 19.5–19.10 Block-Oriented Architecture

Each landing block owns: request record · response record · use case interface · use case implementation · outbound port · adapter · business rules.

Avoid generic DTOs shared by unrelated blocks. Adding a new block requires only: new use case + new adapter + Spring bean registration + Contentstack configuration.

Preserve only external integration contracts. Everything internal must follow Hexagonal Architecture.

**AI Migration Workflow:** 1) Analyze external integration → 2) Identify business capability → 3) Design domain records/sealed interfaces → 4) Design inbound/outbound ports → 5) Design adapters → 6) Design block-specific records → 7) Implement clean solution.

Objective: clean, scalable, maintainable architecture — not feature parity with the legacy system.


# Rule 20 — Constants usage

Always define constants instead literal strings or numbers in the code. This promotes maintainability, readability, and reduces the risk of typos.