---
name: ms-clp-blp-architecture
description: >
  Mandatory structural rules for writing or modifying Java code in ms-clp-blp (brand/category
  landing pages). The codebase is feature-first (screaming architecture + vertical slices) with a
  hexagon inside each slice — the same architecture as ms-home — enforced by ArchitectureTest
  (ArchUnit). Invoke for ANY task that creates, moves, or modifies Java classes: "add an endpoint",
  "new use case", "new block", "new provider", "call a new external service", "add a field",
  "create a class", "refactor", "implement". Explains where every kind of class belongs, the naming
  conventions, and the copy-paste recipes for new blocks and upstreams.
---

# ms-clp-blp Architecture Rules

Top-level packages under `com.liverpool.ms_clp_blp` scream the domain, not the layers:

```
landing/       slice: GET /blp/{locale}/** and GET /clp/{locale}/**
  blocks/      block sub-slices — productslist (POST /blp|clp/blocks/products-list)
  brandflags/  sub-slice: per-brand feature flags (Contentstack webhook → Mongo → cache)
clients/       infra only: Feign clients, *FeignConfig, properties, resilience
shared/        cross-cutting: errors + GlobalExceptionHandler, session, web/security/MDC,
               logging, constants, cachepurge, cmspurgewebhook
```

`src/test/java` mirrors the same tree. Every package has a `package-info.java` stating its purpose
and allowed dependencies. The page is the ms-home composition engine applied to a landing page
entry: see `docs/confluence/overview/architecture.md`.

## Dependency rules — the build fails if you break these

`ArchitectureTest` (ArchUnit, 11 rules) enforces:

1. `landing` may depend on `shared` + `clients`. `shared` and `clients` **never** depend on it.
   A new top-level slice means new rules against `landing`, spelled out explicitly.
2. `landing/blocks/*` may depend on landing-owned contracts (`landing.ports`, `landing.models`,
   `landing.errors`), `shared`, `clients`. **Never** on `landing.adapters` or `LandingUseCases`.
3. `landing` core never imports `landing.blocks.*` — it knows blocks only via the `BlockType`
   enum + the `BlockResolutionCatalog` built per page type in `landing/LandingConfig`.
4. `landing.brandflags` never depends on a block sub-slice.
5. `ports/` and `models/` packages: **zero** `org.springframework.*` / `jakarta.servlet.*`
   imports. Records and sealed interfaces only; no mutable state.
6. `@RestController` classes live only in `..adapters.rest..` packages.
7. Implementations of `ContentPort`, `CategoryPort`, `CategoryRedirectPort`, `FavoriteStorePort` and
   `RestrictedProductsPort` live in `landing.adapters`; `ProductsListPort` implementations in
   `landing.blocks.productslist.adapters`; `BlockResolver`/`BlockParamsBuilder` in a block's adapters.
8. `landing.blocks.productslist` never reads exchange-derived identity from `SessionContext`.

## Anatomy of the slice (what goes where)

| Element | Rule |
|---|---|
| `ports/LandingService` | THE inbound port. New use case = new method here, not a new interface. |
| `ports/<Name>Port` | One outbound port per external dependency (`ContentPort`, `CategoryPort`, `FavoriteStorePort`, `RestrictedProductsPort`). Keep the `*Port` suffix. Best-effort ports answer `Optional.empty()`/empty sets and never throw. |
| `LandingUseCases` | The ONE `@Service` at slice root implementing `LandingService`. |
| `LandingCompositionService` | Pure POJO (no Spring) composing a `LandingDefinition` into a `LandingPage`; one instance per `PageType`, wired as `@Bean`s in `LandingConfig` and collected in `LandingCompositions`. |
| `models/` | All records/sealed types the use cases touch. `PageType` (BLP/CLP) and `PageSection` (top_content/blocks/bottom_content) name what differs between pages. |
| `errors/` | Slice-specific exceptions extending `shared.errors.ClpBlpException`. |
| `adapters/rest/v1/` | `LandingControllerV1` + `LandingApiV1` (all Swagger + validation annotations) + mappers. URLs are version-less (`/blp/...`); the `v1` package is pre-positioning. |
| `adapters/<system>/` | Outbound adapters: `contentstack` (page entry + category redirect), `categoryindexer`, `store`, `blacklist`, `cache`, `featureflag`. They inject the Feign clients from `clients/` — never build their own HTTP client. |

**Naming, when one block has several providers** (`products_list`, ADR-029): provider-agnostic
classes are `ProductsList*`; provider implementations that are Spring beans are
`ProductsList<Provider>*` (adapters, block resolvers); framework-free records and `new`-ed helpers are
`<Provider>*` (`JewelSettings`, `VertexProductMapper`).

Wiring conventions: constructor injection only. Circuit breakers are programmatic Resilience4j at
the call site (one shared `CircuitBreakerRegistry`, `CircuitBreakerTransitionLogger` logs every
transition). No retries. Errors surface as `ClpBlpException` subclasses;
`shared/errors/GlobalExceptionHandler` translates to RFC 7807 — never add per-controller handling.

## "Where does my code go?" quick table

| Task | Location |
|---|---|
| New endpoint on landing pages | Method on `LandingService` + `LandingUseCases` + `LandingControllerV1`/`LandingApiV1` |
| New `products_list` provider | One adapter under `landing/blocks/productslist/adapters/<provider>/` implementing `ProductsListPort` (+ `BlockResolver`, `BlockParamsBuilder`), one `SourceOfData` value, one flag in `LandingFeatureFlags` + `landing.feature-flags.*`, one entry in `LandingConfig.catalogFor`. Never a new slice. |
| New dynamic block type | New sub-slice under `landing/blocks/` (copy `productslist/`), `BlockType` constant with `BlockKind.DYNAMIC`, catalog entry in `LandingConfig.catalogFor`, its endpoint on both `/blp/blocks/...` and `/clp/blocks/...` |
| New static block type | Nothing is required — unknown types pass through. Add a `BlockType` constant only if a composition rule must recognise it. |
| New page section | One `PageSection` constant naming its source keys and response key |
| New best-effort upstream for the page | `clients/<system>/` (Client, FeignConfig, Properties, dto), port in `landing/ports`, adapter in `landing/adapters/<system>/` with its own breaker and cache, called from `LandingUseCases` on a virtual thread |
| Constant used by one class | `static final` in that class; shared literals in `shared/constants/ClpBlpConstants` |
| Response shape change | Mapper in `adapters/rest/v1/` — domain models never serialize directly |

## Verification (required before finishing any structural change)

```bash
./mvnw test -Dtest=ArchitectureTest   # boundary rules
./mvnw verify                         # full suite + JaCoCo ≥ 85% + spotless:check
```

Tests mirror the slice's package; name them `<ClassName>Test`. `LandingEndToEndFeignTest` composes
the real `page_blp` capture end to end over WireMock — extend it when a change touches the page.

## Known deviations — do not "fix" these

- `DynamicBlockServiceUnavailableException` lives in `shared/errors` because
  `GlobalExceptionHandler` handles it specifically.
- `CmsProperties` (in `clients/`) is also read by `shared/session/SessionContextAdapter` for the
  default brand. Pre-existing smell inherited from ms-home.
- `landing/blocks/productslist/` owns `ProductsListProperties` (prefix `products-list`) at its root,
  because the edge-cache TTL is an endpoint concern, not a provider connection setting.
- `products_list` resolves through a **batch** endpoint (`BlockResolution.batch`): the advertised url
  is the bare path, posted with an array of blocks. `BlockResolutionContractTest` pins the `ms_params`
  of every catalog key for both page types.
- The product blacklist is applied in `ProductsListUseCases`, not in the page composition, so the
  inlined carousel and the lazy batch endpoint filter identically.
