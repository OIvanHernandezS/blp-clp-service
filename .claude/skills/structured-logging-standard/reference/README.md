# Reference sources

Extracted from `ms-home`, the reference implementation. `<service>` in the package declarations is a
placeholder — replace it, and change nothing else in the files marked *verbatim*.

| File | Port how | Why |
|---|---|---|
| `GcpStructuredLogFormatter.java` | **Verbatim** (set `SERVICE_NAME`) | The enabler. Every detail in it — reserved-key skipping, native numbers, top-level placement — is load-bearing; see the skill |
| `NullSafeEventBuilder.java` | **Verbatim** | Pure delegation. The only interesting methods are the two `addKeyValue` overloads |
| `MdcPropagation.java` | **Verbatim** | Needed wherever work crosses a thread boundary |
| `Causes.java` | **Verbatim** | Lives in `shared/errors`, not `shared/logging` |
| `LogFields.java` | **Adapt** | Keep the shared vocabulary exactly; replace the domain-specific block |
| `LogEvents.java` | **Adapt** | The catalogue is per-service. Keep the `at(...)` helpers exactly |

## What must not drift between services

These key names are the cross-service query surface. Same concept, same key, everywhere:

```
event  reason  upstream  upstreamOperation  upstreamStatus  httpStatus  elapsedMs
errorCode  category  retryable  errorClass  causeChain  detail  breaker  uri
```

Everything under "block context" and "composition summary" in `LogFields` is ms-home's domain and
should be **replaced**, not carried over — a `blockId` field in a service with no blocks is noise.

Likewise in `LogEvents`: `UPSTREAM_CALL_*`, `CIRCUIT_BREAKER_OPEN`, `DOMAIN_ERROR`,
`VALIDATION_FAILED` and `UNEXPECTED_ERROR` are generic and worth keeping; `HOME_COMPOSED`,
`BLOCK_*` and `CONTENT_SERVED` are ms-home's and each service names its own equivalents.

Keep the enum's shape: one constant per outcome, each pinning its own `Level`, plus the two static
`at(...)` helpers. That shape is what stops the level from being decided at the call site.

## Wiring

```yaml
# application.yaml
logging:
  structured:
    format:
      console: com.liverpool.<service>.shared.logging.GcpStructuredLogFormatter
  level:
    root: INFO
    com.liverpool.<service>: ${LOG_LEVEL_APP:INFO}

management:
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:1.0}
```

For a readable local console, add `%kvp` to `logging.pattern.console` — without it the structured
fields are invisible until deployed. Keep the correlation ids out of that pattern: the JSON format
still carries all four, and a full `traceId`/`spanId`/`correlationId`/`requestId` block is ~130
characters of hex ahead of every message. `%.-8X{requestId:-------}` is enough to tell concurrent
local requests apart.
