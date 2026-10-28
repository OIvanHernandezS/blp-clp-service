---
name: structured-logging-standard
description: >-
  The concrete logging wire format shared across Liverpool Java services: stable event codes,
  queryable key-value fields instead of prose, one INFO line per request, and one runnable curl per
  outbound call. Invoke for ANY task that writes or changes a log statement — "add logging", "log
  this", "why is this failing in prod", "I can't tell why the block disappeared", "the console is
  flooded", "add an event", "make this debuggable" — and whenever porting the standard to another
  service (ms_pdp_search, ms_plp_search, platform_commons). Defines the four classes to port, the
  level policy, the naming rules, and the traps that cost real incidents: MDC lost in virtual
  threads, Feign's NONE level killing latency measurement, null fields rendering as "null".
---

# Structured Logging Standard

## What this is, and what it is not

`logger-handler` owns the **philosophy**: what an exception must expose, `message` vs `detail`, log
once at the highest layer, do not log what nobody will read. That still applies in full.

This skill owns the **wire format**: exactly how a line is shaped so it can be queried, and which
four classes make that possible. When the two seem to disagree, they do not — see
[Reconciling with `logger-handler`](#reconciling-with-logger-handler) at the end.

Reference implementation: `ms-home`. Portable sources in `reference/`.

---

## The one rule

**Context travels as fields, never inside the message.**

A message is prose for a human; every value a machine might filter on is a key-value pair. This is
the whole standard — everything below serves it.

```java
// ✗ Never. The data is trapped in a string; filtering needs a regex over textPayload.
log.warn("Block resolution failed for blockId={} type={}; degrading: {}",
        uid, type, e.getCause().getMessage());

// ✓ Always.
LogEvents.at(log, LogEvents.BLOCK_DEGRADED)
        .addKeyValue(LogFields.BLOCK_ID, uid)
        .addKeyValue(LogFields.BLOCK_TYPE, type)
        .addKeyValue(LogFields.UPSTREAM_STATUS, status)
        .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(cause))
        .log("Block resolution failed; degrading the block");
```

The payoff is that operational questions become queries:

```text
jsonPayload.event="BLOCK_DROPPED" AND jsonPayload.blockId="blt123"
jsonPayload.event="UPSTREAM_CALL_FAILED" AND jsonPayload.elapsedMs>2000
```

Uses SLF4J 2's fluent API. **No new dependency** — it ships with Spring Boot 3+. Logback 1.3+ exposes
the pairs via `ILoggingEvent.getKeyValuePairs()`.

---

## The four classes to port

Copy from `reference/`, adapt the package, change nothing else.

| Class | Why it exists |
|---|---|
| `GcpStructuredLogFormatter` | Promotes key-value pairs to **top-level** JSON fields. Without it the pairs are dropped and the whole standard is decorative |
| `LogFields` | The key names. They **are** the query surface: a rename silently breaks every saved query, dashboard and log-based alert |
| `LogEvents` | The event catalogue. Each event pins **its own level**, so the level stops depending on who wrote the line |
| `NullSafeEventBuilder` | Drops null-valued pairs. `errorClass="null"` reads like data when it means absence |

`MdcPropagation` is a fifth, required only where work crosses threads — see
[Correlation](#correlation).

### Non-negotiable details in the formatter

- **Reserved keys are skipped**, not overwritten: `severity`, `message`, `time`, `logger`, `service`,
  `thread`, `stack_trace`, `logging.googleapis.com/*`. A pair reusing one would clobber the field
  Cloud Logging classifies on and the line lands as `DEFAULT` — where cost-exclusion filters drop it.
- **Numbers and booleans stay native.** `jsonPayload.elapsedMs>2000` does not work against a string.
- **Never emit ECS.** Cloud Run only classifies a line by a *top-level* `severity`; ECS nests it under
  `log.level`, so every line lands as `DEFAULT` and is silently discarded.

---

## Level policy

| Level | What goes there |
|---|---|
| `ERROR` | Only the unexpected: the catch-all advice, startup/config failure, and **a failure whose cause is not one of your typed domain exceptions** — that is a defect in *this* service, not someone else's outage, so it carries a stack trace |
| `WARN` | Degradation that changes what the user sees: degraded block, failed outbound call, breaker open, session dropped to guest |
| `INFO` | **One summary line per request.** Nothing else |
| `DEBUG` | Per-unit detail: drops, skips, resolutions, the outbound `curl` |

### The one-INFO-per-request rule

This is the rule people break. Entry lines (`"GET /home brand=..."`), progress lines
(`"Resolving X"`) and completion lines (`"X resolved"`) say nothing a summary cannot, and they
multiply: they turn one request into a dozen lines nobody reads and everybody pays to ingest.

Emit **one line, on the way out**, carrying the outcome and `elapsedMs`:

```text
event=HOME_COMPOSED blocksTotal=25 blocksServed=18 blocksDropped=7 blocksDegraded=0
  dropsByReason={AUDIENCE_MISMATCH:3, RESOLVER_RETURNED_EMPTY:1} elapsedMs=118
```

A request that quietly degrades is now visible **without turning any level up**. Accumulate the
counters in a small per-request trace object (concurrent if any work fans out) and emit in the
outermost method.

### Suppressed lines are free — use DEBUG generously

`LogEvents.at()` returns SLF4J's NOP builder unwrapped when the level is off, so a suppressed line
costs ~3 ns (measured; *cheaper* than the classic API's varargs array). Put real diagnostic detail at
DEBUG. Two conditions:

- **No eager work in the arguments.** `.addKeyValue(K, "text " + expensive())` runs even when
  suppressed. Pass the variable as its own field and keep the message constant — which also makes it
  queryable.
- Guard genuinely expensive rendering with `log.isDebugEnabled()`.

---

## Naming

**Events** — `SCREAMING_SNAKE`, `<SUBJECT>_<PAST-TENSE OUTCOME>`: `BLOCK_DROPPED`,
`UPSTREAM_CALL_FAILED`, `SESSION_DEGRADED_TO_GUEST`. Name the outcome, not the code path. One event
per *thing that happened*, distinguished by a `reason` field rather than by proliferating events.

**Fields** — `camelCase`, declared as constants in `LogFields`, never as inline literals. Reuse
before inventing: the same concept must carry the same key in every service, or cross-service queries
stop working.

The shared vocabulary — do not rename these:

`event` · `reason` · `upstream` · `upstreamOperation` · `upstreamStatus` · `httpStatus` ·
`elapsedMs` · `errorCode` · `category` · `retryable` · `errorClass` · `causeChain` · `detail` ·
`breaker` · `uri`

`requestId`, `correlationId` and `operation` come from MDC and must **not** be re-attached as pairs.

---

## Failure context

Make the exception carry its own fields, so the single translation point needs no `instanceof` chain
and a new exception type is diagnosable the day it is written:

```java
public abstract class ServiceException extends RuntimeException {
    /** Extra structured fields, keyed by LogFields names. Empty by default. */
    public Map<String, Object> context() { return Map.of(); }
}
```

The handler dumps `ex.context()` and adds the common fields. Subclasses override to add what only
they know (`blockId`, `upstream`, `upstreamStatus`).

Two fields answer "what actually blew up" without paying for a stack trace — `Causes` in
`reference/`:

| Field | Value |
|---|---|
| `causeChain` | `DynamicBlockServiceUnavailableException <- FeignException$GatewayTimeout <- SocketTimeoutException` |
| `detail` | The **root** cause's message — where the host and port appear |

Log expected degradation at WARN **without** a stack trace: it is expected, and one request may
degrade several units. Reserve stack traces for untyped causes.

**When merging fields from several sources, merge into a `Map` before emitting.** Attaching the same
key twice renders it twice in the console pattern.

---

## Outbound calls

At most **two lines per call**, and none at INFO.

| Level | Emitted |
|---|---|
| `INFO` | Nothing. A healthy call is not news |
| `WARN` | One line per failure: `upstream`, `httpStatus`, `elapsedMs`, and the upstream's **error body** |
| `DEBUG` | One runnable `curl` per request, one line per response |

Render the request as `curl`, not as a header-per-line dump: during an incident the point of reading
it is to replay it. Feign's `Level.FULL` at INFO emits ~30 lines per call and buries everything else.

**Always log the upstream's error body** (truncated, ~500 chars). `httpStatus=404` does not say why;
`{"code":"TOKEN_NOT_FOUND"}` does. Reading it obliges you to re-buffer the response so the decoder
still sees it.

**Mask by header name, always** — `authorization`, `proxy-authorization`, `apikey`, `api-key`,
`x-api-key`, `cookie`, `set-cookie`, `sessionid`, identity headers — in headers *and* in the query
string. Show that the header was **present** and redact only the value: against a 401,
`authorization: ***` and no `authorization` line at all mean very different things.

Bodies are the exception: gate them behind a flag, off by default, and say so in the docs.

### Trap: `Logger.Level.NONE` kills your latency measurement

Feign skips `logRequest`/`logAndRebufferResponse` entirely at `NONE` — and those hooks are where the
elapsed time is handed to you. Use `BASIC` and override the hooks. In most services this is the
**only** measurement of upstream latency that exists.

---

## Correlation

An MDC filter at the front of the chain populating `requestId` (echo `x-request-id` or generate),
`correlationId` (echo `x-correlation-id`, else `requestId`) and `operation` (method + URI), cleared in
`finally`.

Never add an MDC key named `service`: it collides with the reserved field and the encoder drops the
event.

### Trap: the MDC does not cross into virtual threads

Logback stopped backing the MDC with an `InheritableThreadLocal`, so **a task submitted to
`Executors.newVirtualThreadPerTaskExecutor()` starts with an empty MDC** — verified against Logback
1.5.34. The lines that most need correlating (a degraded unit, a failed call) are exactly the ones
that lose it, and nothing fails loudly.

Wrap every submit with `MdcPropagation.wrap(...)` (`reference/`). Before applying it, **write the test
that proves the context is lost** — it is the only thing that stops the wrap being removed later as
"pointless".

### Trap: default sampling silently breaks trace correlation

Spring Boot samples at `0.1`, and sampling decides whether `traceId`/`spanId` reach the MDC at all —
so ~90% of lines cannot be joined to the platform's request log. Set it explicitly:

```yaml
management.tracing.sampling.probability: ${TRACING_SAMPLE_RATE:1.0}
```

With no exporter configured this costs ~111 ns/request (measured): the ids are produced for the log,
not shipped anywhere. Lower it only when an exporter is added.

---

## Anti-patterns

| Do not | Do |
|---|---|
| `log.info("Resolving X…")` before the work | One summary line after it |
| Values inside the message string | `addKeyValue` |
| The same message text copy-pasted across adapters | One event, an `upstream`/`source` field |
| The same situation at WARN in one adapter and INFO in another | The level comes from the event catalogue |
| A silent `continue` / `return null` that drops user-visible output | Name the reason in an enum and log it |
| `String.format(...)` inside a log call | Placeholders, or the fluent API |
| A verbose trace visible only via a flag | Visibility is the **level**; the flag adds bodies |
| Logging a failure at each layer | Log once, at the highest layer that has the whole context |

---

## Before you finish

1. **Does a suppressed line cost anything?** No eager concatenation or method calls in the arguments.
2. **Is every new key a `LogFields` constant?** No inline literals.
3. **Does the event carry a level from the catalogue,** rather than one chosen at the call site?
4. **Is there exactly one INFO line per request?** Count them in a test.
5. **Can a null field appear?** It must be omitted, not rendered as `"null"`.
6. **Does anything cross a thread boundary?** Then it needs `MdcPropagation.wrap`.
7. **Would the line survive a rewording?** If the answer needs the prose, the data belongs in a field.

Pin the behaviour with a `ListAppender<ILoggingEvent>` and assert on `getKeyValuePairs()` — assert the
fields, never the message text.

---

## Measured cost

From `ms-home`, best-of-5 × 1M iterations after warmup. Useful when someone objects that the fluent
API is slower:

| | classic | fluent |
|---|---|---|
| Suppressed line (the common case in prod) | 6 ns | **3 ns** |
| Emitted line, call site | 44 ns | 90 ns |
| Emitted line + JSON encode | 731 ns | 1087 ns |

The emitted-line delta is not overhead: the fluent version serializes six *more* fields that the
classic one had crammed into the message. You pay for queryability, only on lines you actually emit.

Net effect in ms-home: a `GET /home` went from ~18 emitted lines to **1** — roughly 12× less logging
work and ~94% less ingested volume on the hot path.

---

## Reconciling with `logger-handler`

| It says | Read it as |
|---|---|
| "Do not introduce middleware, filters or interceptors **solely for error handling**" | Correct. The MDC filter and the outbound logger are **observability**, not error handling; neither translates an error |
| "Never log large payloads" | Still holds. Upstream **error** bodies are truncated (~500 chars) and only on failure — a bounded diagnostic, not a payload dump |
| "Structured, parameterized logging; never concatenate" | This skill is how: key-value pairs rather than placeholders in prose |
| "Log a failure exactly once, by the highest responsible layer" | Unchanged, and the exception's `context()` is what lets that one line carry everything |
