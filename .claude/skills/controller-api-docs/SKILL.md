---
name: controller-api-docs
description: >
  How to document REST endpoints in ms-clp-blp with SpringDoc OpenAPI 3 (fulfils java-code-quality
  Rule 8.1). Invoke for ANY task that adds or changes a controller endpoint, a request/response DTO,
  a status code, a header, or a query/path parameter: "add an endpoint", "new controller", "new
  block resolution endpoint", "change the response shape", "document this API", "the Swagger docs are
  wrong". Explains the interface-based annotation pattern (`*ApiV1`), where each annotation goes, the
  copy-paste recipe, the shared-header rule, and the verification steps. The OpenAPI spec is
  incomplete work until it matches the implementation.
---

# ms-clp-blp Controller API Documentation Rules

Every exposed HTTP endpoint is documented with **SpringDoc OpenAPI 3** (`java-code-quality` Rule
8.1). The spec is generated at runtime — nothing is committed — and served at:

- `GET /v3/api-docs` — the OpenAPI 3.1 document
- `/swagger-ui.html` — Swagger UI (gated by `springdoc.swagger-ui.enabled`; **off in `prod`**)

The dependency is `org.springdoc:springdoc-openapi-starter-webmvc-ui` (the **3.x** line — the 2.x
line targets Spring Boot 3 and will not load on this Boot 4 codebase). Global config lives in
`shared/web/OpenApiConfig.java`.

## The pattern: annotations live on a `*ApiV1` interface, not the controller

Controllers stay thin adapters (see `ms-clp-blp-architecture`). All Swagger annotations go on a
documentation **interface** named `<Slice>ApiV1`, in the same `adapters/rest/v1` package as the
controller. The controller `implements` it and adds `@Override`; it keeps its Spring routing and
validation annotations. SpringDoc merges the OpenAPI metadata from the interface onto the handler.

| Annotation | Where it goes |
|---|---|
| `@Tag`, `@Operation`, `@ApiResponse(s)`, `@Parameter`, swagger `@RequestBody`, `@ExampleObject` | the `*ApiV1` **interface** |
| **`jakarta.validation` constraints** (`@NotBlank`, `@Size`, `@Min`, `@NotEmpty`, …) | the `*ApiV1` **interface** method params (see HV000151 below) |
| `@RestController`, `@RequestMapping`/`@GetMapping`/`@PostMapping`, `@PathVariable`, `@RequestParam`, spring `@RequestBody` | the **controller** (unchanged) |
| `@Schema` (field descriptions + examples) | the **response DTO** record components |

### Validation constraints MUST live on the interface (HV000151)

The controllers are `@Validated`, so method validation runs. Bean Validation forbids an **overriding
method from declaring parameter constraints** that the interface method lacks — a controller method
that `@Override`s the interface and still carries `@NotBlank`/`@Size`/`@Min`/`@NotEmpty` fails at
runtime with:

> `jakarta.validation.ConstraintDeclarationException: HV000151: A method overriding another method
> must not redefine the parameter constraint configuration`

which surfaces as a **500 on every request**. So put the `jakarta.validation` constraints on the
`*ApiV1` interface method parameters and leave only the Spring binding annotation (`@PathVariable` /
`@RequestParam` / `@RequestBody`) on the controller param. Shared limits (e.g. block-id max length)
become interface constants (`int BLOCK_ID_MAX_LENGTH = 128;`).

### Never redeclare the shared context headers

`x-brand-id`, `x-channel`, `x-locale` and the `SessionId` cookie are resolved server-side via
`SessionContextPort`, not as method parameters. They are documented **once** for every operation by
the `sessionContextParameters()` `OperationCustomizer` bean in `OpenApiConfig`. Do **not** add them
to any interface. Endpoint-specific signals like the `x-preview` header can be documented per method
with `@Parameter(in = ParameterIn.HEADER, ...)`.

## Recipe — a new endpoint

1. Create `<Slice>ApiV1.java` next to the controller:

```java
@Tag(name = "Block: my-thing", description = "Resolves the my_thing block.")
public interface MyThingApiV1 {

    int BLOCK_ID_MAX_LENGTH = 128;

    @Operation(summary = "Resolve the my-thing block",
            description = "What it does, when it 502s/503s, and any body semantics.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resolved payload.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MyThingResponse.class),
                            examples = @ExampleObject(name = "ok", value = """
                                    { "blockId": "blt...", "title": "..." }"""))),
            @ApiResponse(responseCode = "502", description = "Backing service errored.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Circuit breaker open.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<MyThingResponse> resolveMyThing(
            @Parameter(description = "Placeholder block uid.", example = "blt...")
            @NotBlank @Size(max = BLOCK_ID_MAX_LENGTH) String blockId);
}
```

2. On the controller: add `implements MyThingApiV1`, add `@Override` on the handler, and **remove any
   `jakarta.validation` constraints** from the handler params (they now live on the interface —
   HV000151). Keep the Spring binding annotation (`@PathVariable`, etc.). Change nothing else.

3. On the response DTO record: add a class-level `@Schema(description = ...)` and a `@Schema` with a
   `description` + `example` on each component.

### Conventions

- **Error responses**: always `@Schema(implementation = ProblemDetail.class)` with media type
  `application/problem+json` — `GlobalExceptionHandler` produces RFC 7807 for every 4xx/5xx. Document
  the statuses the endpoint actually returns (dynamic blocks: `502` backing error, `503` breaker
  open; batch endpoints: `400` validation, `200` with per-item status for partial failure).
- **Examples**: prefer realistic values in the domain's language (Spanish product titles, `blt…`
  Contentstack uids, `es-mx` locale). Use Java text blocks (`"""`) for JSON.
- **`ResponseEntity<T>`**: SpringDoc unwraps it — document the body type `T`, not the wrapper.
- **`HttpServletRequest`** and other framework params: hide with `@Parameter(hidden = true)`.
- **Opaque map / external-DTO bodies** (`LandingPageResponse`'s `root`, `ProductRecommendation`): set
  `@Schema(type = "object")` and lean on a good `@ExampleObject` rather than a generated schema.

## Before you finish

- [ ] Every new/changed operation has a `@Tag`, an `@Operation` summary, and an `@ApiResponse` per
      status it can return (including the ProblemDetail error paths).
- [ ] Request/response DTO components carry `@Schema` descriptions and examples.
- [ ] No shared context header (`x-brand-id`/`x-channel`/`x-locale`/`SessionId`) is redeclared on an
      interface.
- [ ] `jakarta.validation` constraints live on the interface params, **not** on the `@Override`
      controller params (HV000151 → 500s otherwise). A `curl` with an invalid value returns 400.
- [ ] The controller only gained `implements <Slice>ApiV1` + `@Override` (and shed its param
      constraints) — no logic or routing moved.

Verify:

```bash
./mvnw -o compile                          # annotations resolve
./mvnw test -Dtest=ArchitectureTest        # interfaces don't break slice boundaries
# then, with the app running and SWAGGER_ENABLED=true:
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'   # your path is listed
# and open http://localhost:8080/swagger-ui.html to eyeball the operation + examples
```

The spec must always match the code: any endpoint, status, header, or DTO change is **not done**
until its OpenAPI annotations are updated.
