---
id: plataforma.migracion-desde-web-bff
title: "Migración desde web-bff"
type: how-to
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-29
sources:
  - src/main/java/com/liverpool/ms_clp_blp/landing/adapters/rest/v1/
  - src/main/java/com/liverpool/ms_clp_blp/landing/blocks/productslist/adapters/rest/v1/
  - src/main/java/com/liverpool/ms_clp_blp/shared/session/
  - src/main/java/com/liverpool/ms_clp_blp/shared/web/
  - scripts/e2e-web-bff-parity-check/
tags: [migracion, web-bff, blp, clp, products_list, frontend]
audience: [frontend, backend, qa]
summary: >
  Qué cambia para el frontend al pedir las BLP y CLP a ms-clp-blp en lugar de web-bff: rutas,
  headers, credencial, la carga lazy de los carruseles y las diferencias de la respuesta, más cómo
  verificar la paridad de datos antes de mover tráfico.
---

# En 30 segundos

- **Rutas:** `GET {web-bff}/content/page-blp|page-clp/{locale}/{path}` pasa a
  `GET {ms-clp-blp}/blp|clp/{locale}/{path}`; `POST /content/populate` pasa a
  `POST /blp|clp/blocks/products-list`.
- **Lo nuevo que debe mandar el frontend:** la credencial Basic, `x-is-logged-in` (decide la audiencia)
  y `x-sf-user-id` para los carruseles de Salesforce.
- **Lo nuevo que debe hacer:** pedir después del primer `GET` los carruseles que lleguen como `LAZY` y
  quitar `globalData` de lo que espera (sale de ms-home).
- **Los datos son los mismos:** la prueba de paridad E2E compara las dos respuestas campo por campo.

# Paso 1 — Cambiar las rutas

| web-bff | ms-clp-blp | Notas |
|---|---|---|
| `GET /web-bff/content/page-blp/{locale}/{path}` | `GET /blp/{locale}/{path}` | `locale` con forma `es-mx` |
| `GET /web-bff/content/page-clp/{locale}/{path}` | `GET /clp/{locale}/{path}` | Igual |
| `POST /web-bff/content/populate` con `{ "blocks": [...] }` | `POST /blp/blocks/products-list` (o `/clp/...`) con `[...]` | El cuerpo es el arreglo directo, máximo 50 bloques. Usa la `url` que trae cada bloque, no la armes |
| `?populateContent=false`, `x-hide-navigation`, `x-is-static` | — | No existen: la página siempre se compone igual |
| — | `?eagerOffset=N` | Opcional: cuántos carruseles se resuelven dentro de la página |

La página se busca por el último segmento de la ruta (`category_id`), tal como llega y en
minúsculas ([ADR-001](../reference/adrs.md#adr-001)). Por eso una URL con el id en mayúsculas
(`/tienda/navidad/CATST43918625`) responde 200 aquí y 404 en web-bff: es lo correcto.

# Paso 2 — Mandar los headers correctos

| Header / cookie | En web-bff | En ms-clp-blp |
|---|---|---|
| `Authorization: Basic …` | No se pedía | **Obligatorio** en todo endpoint; sin él, 401 `UNAUTHORIZED`. Es una credencial de servicio, no del usuario |
| `x-brand-id` | Marca | Igual. Sin él se usa `LP` |
| `x-channel` | Canal | `WEB`, `APP`, `KIOSCO` o `POS` (kiosco y POS se comportan como `WEB`). Sin él, `WEB` |
| `x-is-logged-in` | Se deducía del intercambio de `SessionId` con el servicio de autenticación | **Lo declara el frontend.** `true` = logueado, `false` = invitado. Decide la audiencia de los bloques y si el edge puede cachear la respuesta. Sin él se trata como invitado y la respuesta no se cachea |
| Cookie `SessionId` | Se intercambiaba por la identidad | Solo se revisa que exista y tenga formato válido; nunca se llama a autenticación ([ADR-007](../reference/adrs.md#adr-007)) |
| `x-sf-user-id` | El id salía del intercambio de sesión | **Lo manda el frontend**: el id ATG para los carruseles de Salesforce V1. Se usa solo si viaja una `SessionId` válida |
| `x-jml-user-id` / `x-jml-device-id` | Igual | Igual (Jewel) |
| `x-sf-device-id` | — | Salesforce V2 |
| Cookie `favoriteStore` | Tienda para la analítica | Igual, pero vuelve la respuesta personal (`no-store`), ver [ADR-005](../reference/adrs.md#adr-005) |
| `x-preview` | Contenido en vista previa | Se ignora |

CORS ya permite todos estos headers, incluidos `x-auth-version` y `x-client-platform`, que se siguen
aceptando aunque ya no se usan.

# Paso 3 — Resolver los carruseles lazy

En web-bff la página llegaba con todos los carruseles resueltos. Aquí cada `products_list` trae un
sobre que dice si ya viene resuelto:

| `loadStrategy` | `status` | Qué hacer |
|---|---|---|
| `EAGER` | `AVAILABLE` | Ya trae `products`: pintarlo |
| `LAZY` | — | Pedirlo después del primer `GET` |
| cualquiera | `UNAVAILABLE` | El proveedor falló: pintar el fallback y reintentar más tarde |

Cuántos llegan resueltos depende de la audiencia: **invitado, todos** (`LANDING_EAGER_OFFSET_GUEST`,
100); **logueado, ninguno** (`LANDING_EAGER_OFFSET_LOGGED`, 0), para que la página pinte al instante y
cada carrusel personalizado llegue por separado.

Para los `LAZY`, junta sus `ms_params` y haz **un** `POST` a su `url` (variables del cURL:
[Cómo usar los cURL](../funcionalidades.md#como-usar-los-curl)):

```bash
curl -s "$MS_CLP_BLP_URL/blp/blocks/products-list" \
  -H 'content-type: application/json' -H 'x-brand-id: LP' -H 'x-channel: WEB' \
  -H 'x-is-logged-in: true' -H 'x-sf-user-id: 31216912118' \
  --cookie "SessionId=$MS_CLP_BLP_SESSION_ID" \
  -d '[{"uid":"blt6325518c968643cd","source_of_data":"vertex","products_data":{"category":"catst26617625"},"min_of_products":"6","max_of_products":"20"}]'
```

La respuesta trae un elemento por bloque, en el mismo orden:

| `status` | Qué hacer |
|---|---|
| `AVAILABLE` | Copiar `content.products`, `content.productsListId` y `content.products_list_title` al bloque y pintarlo |
| `EMPTY` | Quitar el bloque; **no** reintentar (`reason` dice por qué: menos de `min_of_products`, sin identidad…) |
| `UNAVAILABLE` | Fallback y reintento (`UPSTREAM_FAILED`, `CIRCUIT_BREAKER_OPEN`) |

El resultado (página + carruseles lazy) tiene los mismos datos que la página de web-bff. Los campos
del sobre (`loadStrategy`, `status`, `url`, `httpMethod`, `resolutionPath`, `ms_params`) son nuevos y
web-bff no los manda. Contrato completo: [Carruseles de productos](../funcionalidades/carruseles-de-productos.md#implementacion).

Un carrusel `source_of_data: groupby` (obsoleto) se resuelve con Vertex y se publica como `vertex`.
Su `ms_params` conserva `groupby`; mándalo tal cual.

# Paso 4 — Ajustar lo que el frontend lee de la respuesta

| Campo | web-bff | ms-clp-blp | Qué hacer |
|---|---|---|---|
| `globalData` | Incluido (~72 KB) | **No viene** | Tomarlo de `GET /global-data` de ms-home ([ADR-002](../reference/adrs.md#adr-002)) |
| `me`, `shortcuts` | Para logueado, junto a la página | No vienen | Pedirlos a sus propios servicios |
| `header` / `footer` | `null` si la página no los sobrescribe | Ausentes | Tratar ausente igual que `null` |
| `categoryData` de una categoría inexistente | Objeto de relleno ("Category id not found") | Ausente | Tratar como "sin categoría" |
| CLP con el indexador caído | 404 | 200 sin `categoryData` ([ADR-003](../reference/adrs.md#adr-003)) | Pintar la página sin árbol |
| `products[].images` | Toda la galería | La primera (`largeImage`), según `PRODUCTS_LIST_MAX_IMAGES` | Ninguno, si solo se pinta una imagen |
| Errores | Formatos varios | RFC 7807 (`application/problem+json`) con `errorCode` | Leer `errorCode`; tabla en [Páginas BLP y CLP](../funcionalidades/paginas-blp-clp.md#implementacion) |

Headers de respuesta nuevos y útiles: `x-request-id` (para buscar la petición en los logs),
`x-data-origin` (`ContentStack` o `caffeine`) y `x-app-version`.

# Paso 5 — Verificar la paridad antes de mover tráfico

La prueba E2E levanta ms-clp-blp con el `.env` del ambiente y compara los **datos** de cada página de
`scripts/e2e-web-bff-parity-check/categories-to-test.csv` contra web-bff, incluidos los carruseles
lazy resueltos como lo haría el frontend:

```bash
scripts/e2e-web-bff-parity-check/e2e-test.sh --prod   # o --dev / --qa
```

Deja un `report.md` con las rutas JSON que difieren y termina con código `1` si hay diferencias.
Opciones y cómo leer cada diferencia: `scripts/README.md`, sección «Paridad E2E de datos contra
web-bff». Diferencias que son **esperadas**:

- `images[]` más corto (paso 4).
- Un carrusel Vertex que cambia solo hacia la posición 8: es el producto patrocinado que ms_plp_search
  rota en cada petición, también en web-bff.
- 404 de web-bff contra 200 de ms-clp-blp con el id en mayúsculas (paso 1).
- `categoryData` de relleno de web-bff (paso 4).

Agrega al CSV las páginas que tu cambio toca y córrela en el ambiente al que vas a migrar.

# Paso 6 — Mover el tráfico

1. Confirma que las variables del ambiente están arriba en Cloud Run (`sync-cloud-run-env.sh`, ver
   [Cloud Run Environment Sync](cloud-run-env-sync.md)) y que la prueba de paridad pasa en ese
   ambiente.
2. Apunta el frontend a ms-clp-blp con los pasos 1 a 4, marca por marca si conviene.
3. Vigila `LANDING_COMPOSED` (una línea por página) y los eventos de degradación descritos en
   [Páginas BLP y CLP](../funcionalidades/paginas-blp-clp.md#soporte) y
   [Carruseles de productos](../funcionalidades/carruseles-de-productos.md#soporte).
4. Para regresar, basta con apuntar el frontend otra vez a web-bff: ms-clp-blp no guarda estado.

# Referencias

- [Páginas BLP y CLP](../funcionalidades/paginas-blp-clp.md) y
  [Carruseles de productos](../funcionalidades/carruseles-de-productos.md): contrato completo y soporte.
- [Configuración](configuracion.md): todas las variables.
- ADRs: [001](../reference/adrs.md#adr-001), [002](../reference/adrs.md#adr-002),
  [003](../reference/adrs.md#adr-003), [005](../reference/adrs.md#adr-005),
  [007](../reference/adrs.md#adr-007).
- Prueba de paridad: `scripts/e2e-web-bff-parity-check/` y `scripts/README.md`.
