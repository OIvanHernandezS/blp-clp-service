# ms-clp-blp

Java 21 / Spring Boot 4.1 microservice that composes the Liverpool e-commerce **brand landing pages
(BLP)** and **category landing pages (CLP)**. It replaces web-bff's
`GET /content/page-blp|page-clp/{locale}/**` and is built exactly like
[ms-home](../dig_crp_home_service): same feature-first hexagonal architecture, same composition
engine (static blocks inline, dynamic blocks eager or lazy), same tooling, skills and workflow. It runs
on **Google Cloud Run** and holds no request state.

- **`GET /blp/{locale}/**`** / **`GET /clp/{locale}/**`** — reads the `page_blp` / `page_clp`
  Contentstack entry whose `category_id` is the last URL segment, composes its sections (`blocks`,
  `top_content`, `bottom_content`), and adds `categoryData` (category indexer tree + CMS redirect).
  Guests get every carousel inlined (`LANDING_EAGER_OFFSET_GUEST`, default 100); logged-in users get
  placeholders (`LANDING_EAGER_OFFSET_LOGGED`, default 0).
- **`POST /blp/blocks/products-list`** / **`POST /clp/blocks/products-list`** — resolves `products_list`
  carousels in one batch against Vertex (also legacy `groupby`), Salesforce, Salesforce V2 or Jewel,
  picked per block by `source_of_data`, with blacklisted products removed.
- **`POST /webhooks/contentstack`** — per-brand feature flags published from Contentstack (MongoDB).
- **`POST /ops/cache-purge**`** and **`POST /webhooks/contentstack/cache-purge`** — Akamai edge purge.

Unlike web-bff, the response carries **no `globalData`**: the storefront reads it from ms-home's
`GET /global-data`.

The official documentation is a published product: it lives in **[docs/confluence/](docs/confluence/)**
(Spanish), is declared in [docs/confluence/confluence-manifest.yml](docs/confluence/confluence-manifest.yml)
and is pushed to Confluence by `scripts/publish_confluence.sh`. The Confluence space does not exist yet
(placeholder key `TODO_CLP_BLP_SPACE`). Start at [docs/confluence/home.md](docs/confluence/home.md).

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1.0 (Spring MVC, Spring Security, Actuator) |
| HTTP clients | OpenFeign (Spring Cloud 2025.1) over OkHttp, one isolated pool per upstream |
| Resilience | Resilience4j core, programmatic circuit breakers, **no retries** |
| Caching | Caffeine (page entry, category tree, store, blacklist) |
| Tracing | OpenTelemetry Java agent (Dockerfile) |
| Logging | Structured JSON for Cloud Logging (`GcpStructuredLogFormatter`) |
| Architecture enforcement | ArchUnit (`ArchitectureTest`) — build fails on layering violations |
| Quality gates | JaCoCo ≥ 85 % lines, Spotless, Codacy (`.codacy/`) |
| Deployment target | Google Cloud Run (`.github/workflows/deploy_DEV_QA.yml`, `deploy_PROD.yml`) |
| Shared DTOs | `com.liverpool:platform-commons` 1.0.1 |

## Architecture at a glance

```
com.liverpool.ms_clp_blp
├── landing/      slice: GET /blp/**, GET /clp/**
│   ├── blocks/   productslist (POST /blp|clp/blocks/products-list)
│   └── brandflags/  per-brand flags (Contentstack webhook → Mongo → cache)
├── clients/      infra only — Feign clients per upstream (contentstackdirect, categoryindexer, store,
│                 blacklist, vertex, salesforce, salesforcev2, jewel, akamai, mongo) + resilience
└── shared/       cross-cutting — errors, session, web/security, logging/MDC, constants, cache purge
```

Rules (enforced by `ArchitectureTest`) and the "where does my code go" table live in the
`ms-clp-blp-architecture` skill (`.claude/skills/`) and in
[docs/confluence/plataforma/arquitectura.md](docs/confluence/plataforma/arquitectura.md).

---

## Prerequisites

- **Java 21 JDK**
- **Access to the private Maven repository** hosting `com.liverpool:platform-commons`
  (`https://us-east4-maven.pkg.dev/sit-cicd-images-apps/liverpool-platform-commons-libraries`) in your
  `~/.m2/settings.xml`.
- No local database: per-brand flags (MongoDB) are off in the `dev` profile.

## Getting started

```bash
cp .env.example .env              # throwaway local credential + every default
# fill CONTENT_STACK_LP_API_KEY / CONTENT_STACK_LP_DELIVERY_TOKEN — without them every page is a 502
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

- App: `http://localhost:8080` · Actuator: `http://localhost:8081/actuator/health` ·
  Swagger UI: `http://localhost:8080/swagger-ui.html`

```bash
curl -s -u local-user:local-pass -H "x-brand-id: LP" -H "x-is-logged-in: false" \
  http://localhost:8080/blp/es-mx/tienda/esteelauder/catst26617625 | jq '{blocks: (.blocks|length), categoryData: .categoryData.id}'
```

`MS_CLP_BLP_SECURITY_USERNAME` / `MS_CLP_BLP_SECURITY_PASSWORD` have no default: the service refuses
to start without them. Every endpoint except Swagger and `/v3/api-docs` requires that Basic credential.

```bash
./mvnw clean package                                  # target/ms-clp-blp-0.0.1-SNAPSHOT.jar
docker build --build-arg ENVIRONMENT=dev -t ms-clp-blp .
```

> **Git hooks install themselves** on the first `./mvnw` run (`core.hooksPath=.githooks/`).

---

## API endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/blp/{locale}/{path}` | Brand landing page (`page_blp`). Optional `?eagerOffset=N` |
| `GET` | `/clp/{locale}/{path}` | Category landing page (`page_clp`). Optional `?eagerOffset=N` |
| `POST` | `/blp/blocks/products-list`, `/clp/blocks/products-list` | Batch: resolves every `products_list` carousel in the body (max 50) |
| `POST` | `/webhooks/contentstack` | Per-brand flags published from Contentstack |
| `POST` | `/ops/cache-purge`, `/ops/cache-purge/{paths,tags,objects}` | Akamai Fast Purge |
| `POST` | `/webhooks/contentstack/cache-purge` | Purge on Contentstack publish (`page_blp`, `page_clp`, `products_list`, `category_redirect`) |
| `GET` | `/actuator/health` etc. | Management port `8081`, never gateway-exposed |

Request signals: `x-brand-id` (brand → Contentstack stack), `x-is-logged-in` (audience; no auth
exchange), `x-channel` (web vs app toggles), cookie `favoriteStore` (analytics `selected_store.*`).
Errors are RFC 7807 `application/problem+json`. Contract, status codes and cURLs:
[docs/confluence/funcionalidades/paginas-blp-clp.md](docs/confluence/funcionalidades/paginas-blp-clp.md)
and [carruseles-de-productos.md](docs/confluence/funcionalidades/carruseles-de-productos.md).

---

## Environment variables

`.env.example` lists every variable `application.yaml` reads, with its default; the full table is in
[docs/confluence/plataforma/configuracion.md](docs/confluence/plataforma/configuracion.md). Highlights:

| Variable | Default | Purpose |
|---|---|---|
| `MS_CLP_BLP_SECURITY_USERNAME` / `_PASSWORD` | *(none — required)* | Inbound HTTP Basic credential |
| `CONTENT_STACK_<STACK>_API_KEY` / `_DELIVERY_TOKEN` | *(empty)* | Contentstack credentials per stack (LP, SB, MULTI1, MULTI2) |
| `LANDING_EAGER_OFFSET_GUEST` / `_LOGGED` | `100` / `0` | Dynamic blocks inlined per audience |
| `FF_VERTEX`, `FF_PRODUCTS_LIST_SALESFORCE`, `FF_PRODUCTS_LIST_SALESFORCE_V2`, `FF_JEWEL` | `true` | One kill switch per carousel provider |
| `FF_CONDITIONAL_SHOW_HIDE` / `FF_CAROUSEL_RESTRICTED_PRODUCTS` | `false` / `true` | A/B cohorts / product blacklist |
| `CATEGORY_INDEXER_URL`, `STORE_SERVICE_URL`, `BLACKLIST_BASE_URL` | site-d hosts | Best-effort upstreams |
| `VERTEX_BASE_URL` + `VERTEX_USERNAME`/`_PASSWORD` | localhost / *(empty)* | ms_plp_search |
| `MONGO_URI`, `BRAND_FLAGS_ENABLED` | *(empty)*, `true` | Per-brand flags store |
| `AKAMAI_*`, `CACHE_PURGE_*` | *(empty)* | Edge purge |

Secrets are injected from Secret Manager at deploy time; `./sync-cloud-run-env.sh --dev|--qa|--prod`
syncs a local `.env` against a Cloud Run service without clobbering it.

---

## Testing

```bash
./mvnw test                           # full suite
./mvnw test -Dtest=ArchitectureTest   # architecture rules only
./mvnw verify                         # suite + JaCoCo ≥ 85 % + spotless:check (what pre-push runs)
```

`LandingEndToEndFeignTest` composes the real `page_blp` entry captured from production
(`src/test/resources/fixtures/page-blp-catst26617625.json`) over real sockets against WireMock stubs of
Contentstack, Vertex, the category indexer, store-service and the blacklist.

## Development workflow

| When | What runs |
|---|---|
| `git commit` | `commit-msg`: Conventional Commits · `pre-commit`: blocks `.env*`, Spotless on staged Java |
| `git push` | `pre-push`: `./mvnw -q verify` when `.java` or `pom.xml` changed |
| push to `develop` | `tag-auto-develop.yml`: SemVer tag from the commit types |
| manual dispatch from a tag | `deploy_DEV_QA.yml` / `deploy_PROD.yml` → Cloud Run |

Branches: `feat/*`, `fix/*`, `docs/*` → PR into `develop`; `main` accepts only `develop` or
`hotfix/*` (`main-branch-guard.yml`). Escape hatch: `SKIP_HOOKS=1` or `--no-verify`. Details:
[docs/confluence/plataforma/git-workflow.md](docs/confluence/plataforma/git-workflow.md).

Before the first deployment: create the GitHub environments (`development`, `qa`, `production`) with
`vars.SERVICE` / `vars.CLOUD_RUN_PROJECT` and their secrets, the Artifact Registry repository
`digital-crp-clp-blp-service`, and the `GOOGLE_CHAT_CLP_BLP_SERVICE` secret.

## Observability

- One INFO line per page: `event=LANDING_COMPOSED` with `pageType`, `blocksTotal`, `blocksServed`,
  `dropsByReason`, `elapsedMs`. Everything per block is DEBUG.
- Degradation events: `CATEGORY_UNAVAILABLE`, `FAVORITE_STORE_UNAVAILABLE`,
  `RESTRICTED_PRODUCTS_UNAVAILABLE`, `BLOCK_DEGRADED`, `CIRCUIT_BREAKER_STATE_CHANGED`.
- Response headers: `x-request-id`, `x-data-origin` (`ContentStack` | `caffeine`), `x-app-version`.
- `LOG_FORMAT=pretty` for a readable local console; `LOG_LEVEL_APP=DEBUG` for the per-call `curl` trace.

## Key design constraints

- One block failure never fails the page; a best-effort upstream never fails the page.
- Contentstack is the only CMS source; pages are looked up by `category_id`, never by `url`.
- No `globalData`, no auth-token exchange, no retries.
- A response carrying a `favoriteStore` store is never shared at the edge.
- Decisions: [docs/confluence/reference/adrs.md](docs/confluence/reference/adrs.md).

## Adding a carousel provider or a block

See the `ms-clp-blp-architecture` skill: a new `products_list` provider is one adapter under
`landing/blocks/productslist/adapters/<provider>/`, one `SourceOfData` value, one flag and one catalog
entry in `LandingConfig.catalogFor` — never a new slice.

## Further reading

- `.claude/skills/` — architecture, code quality, logging, API docs and documentation rules
- [docs/confluence/](docs/confluence/) — official documentation (Spanish)
- [scripts/README.md](scripts/README.md) — Confluence publisher and Cloud Run env sync
