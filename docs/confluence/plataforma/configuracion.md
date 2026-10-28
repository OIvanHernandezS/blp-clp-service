---
id: plataforma.configuracion
title: "Configuración"
type: reference
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-27
sources:
  - src/main/resources/application.yaml
  - src/main/resources/application-dev.yaml
  - src/main/resources/application-qa.yaml
  - src/main/resources/application-prod.yaml
tags: [configuracion, env, banderas, secretos]
audience: [backend, devops, sre, soporte]
summary: >
  Todas las variables de entorno del servicio, agrupadas por sistema, con su valor por defecto. La
  fuente de verdad es `application.yaml`; los secretos se inyectan en Cloud Run desde Secret Manager
  y nunca tienen valor por defecto.
---

# Perfiles

| Perfil | Qué cambia |
|---|---|
| `dev` | Logs en `DEBUG`, Swagger encendido, banderas por marca y purga de caché **apagadas** (no hace falta Mongo ni Akamai) |
| `qa` | Logs en `INFO`, Swagger encendido |
| `prod` | Logs en `INFO`, Swagger apagado, actuator expone solo `health` e `info` |
| `pretty` (`LOG_FORMAT=pretty`) | Consola legible en lugar de JSON (solo local) |

El perfil lo fija el Dockerfile con `-Dspring.profiles.active=$ENVIRONMENT`.

# Banderas {#banderas}

Cada bandera se resuelve por marca, en este orden — la primera que tenga valor gana:

1. El documento `<MARCA>_<PERFIL>` de la colección `ms_clp_blp_configuration` (publicado desde la
   entrada `ms_clp_blp_flags` de Contentstack por `POST /webhooks/contentstack`), con caché de
   `BRAND_FLAGS_CACHE_TTL`.
2. `FF_OVERRIDE_JSON`, un objeto `{"<bandera>_<MARCA>": bool}` para cuando Mongo no está.
3. La variable `FF_*` del servicio.
4. `false`.

Solo existen las banderas de los proveedores de carrusel (`products-list-salesforce`,
`products-list-salesforce-v2`, `jewel`, `vertex`) y las dos de todo carrusel
(`conditional-show-hide`, `carousel-restricted-products`). Cualquier otro campo de la entrada del CMS
se ignora.

# Variables

## Credencial de entrada

Credencial HTTP Basic que todo llamador presenta. **Obligatoria: sin ella el servicio no arranca.**

| Variable | Por defecto | Secreto |
|---|---|---|
| `MS_CLP_BLP_SECURITY_USERNAME` | *(vacío)* | sí |
| `MS_CLP_BLP_SECURITY_PASSWORD` | *(vacío)* | sí |

## Contentstack

Qué tipos de contenido se leen y la caché Caffeine de la entrada.

| Variable | Por defecto | Secreto |
|---|---|---|
| `CMS_BRAND` | `LP` |  |
| `CMS_BLP_TYPE` | `page_blp` |  |
| `CMS_CLP_TYPE` | `page_clp` |  |
| `CMS_CATEGORY_REDIRECT_TYPE` | `category_redirect` |  |
| `CMS_LANDING_CACHE_ENABLED` | `true` |  |
| `CMS_LANDING_CACHE_REFRESH` | `30s` |  |
| `CMS_LANDING_CACHE_EXPIRE` | `5m` |  |
| `CMS_LANDING_CACHE_MAX_SIZE` | `2000` |  |

## Contentstack (credenciales y transporte)

Credenciales por stack (LP, SB, MULTI1 = WE/WS/PB/PBK/DPS/FB/DCK, MULTI2 = GAP/BR/TRU/BRU) y timeouts.

| Variable | Por defecto | Secreto |
|---|---|---|
| `CONTENT_STACK_URL` | `https://gcp-na-cdn.contentstack.com` |  |
| `CONTENT_STACK_BRANCH` | *(vacío)* |  |
| `CONTENT_STACK_ENV` | *(vacío)* |  |
| `CONTENT_STACK_LP_API_KEY` | *(vacío)* | sí |
| `CONTENT_STACK_LP_DELIVERY_TOKEN` | *(vacío)* | sí |
| `CONTENT_STACK_LP_BRANCH` | *(vacío)* |  |
| `CONTENT_STACK_LP_ENV` | *(vacío)* |  |
| `CONTENT_STACK_SB_API_KEY` | *(vacío)* | sí |
| `CONTENT_STACK_SB_DELIVERY_TOKEN` | *(vacío)* | sí |
| `CONTENT_STACK_SB_BRANCH` | *(vacío)* |  |
| `CONTENT_STACK_SB_ENV` | *(vacío)* |  |
| `CONTENT_STACK_MULTI1_API_KEY` | *(vacío)* | sí |
| `CONTENT_STACK_MULTI1_DELIVERY_TOKEN` | *(vacío)* | sí |
| `CONTENT_STACK_MULTI1_BRANCH` | *(vacío)* |  |
| `CONTENT_STACK_MULTI1_ENV` | *(vacío)* |  |
| `CONTENT_STACK_MULTI2_API_KEY` | *(vacío)* | sí |
| `CONTENT_STACK_MULTI2_DELIVERY_TOKEN` | *(vacío)* | sí |
| `CONTENT_STACK_MULTI2_BRANCH` | *(vacío)* |  |
| `CONTENT_STACK_MULTI2_ENV` | *(vacío)* |  |

## Transporte de Contentstack

| Variable | Por defecto | Secreto |
|---|---|---|
| `CONTENT_STACK_DIRECT_CONNECT_TIMEOUT` | `2s` |  |
| `CONTENT_STACK_DIRECT_CALL_TIMEOUT` | `4s` |  |

## Composición

Banderas de los proveedores de carrusel y offsets de resolución en línea por audiencia.

| Variable | Por defecto | Secreto |
|---|---|---|
| `FF_PRODUCTS_LIST_SALESFORCE` | `true` |  |
| `FF_PRODUCTS_LIST_SALESFORCE_V2` | `true` |  |
| `FF_JEWEL` | `true` |  |
| `FF_VERTEX` | `true` |  |
| `FF_CONDITIONAL_SHOW_HIDE` | `false` |  |
| `FF_CAROUSEL_RESTRICTED_PRODUCTS` | `true` |  |
| `LANDING_EAGER_OFFSET_LOGGED` | `0` |  |
| `LANDING_EAGER_OFFSET_GUEST` | `100` |  |

## Endpoint de carruseles

| Variable | Por defecto | Secreto |
|---|---|---|
| `PRODUCTS_LIST_CACHE_TTL` | `300` |  |
| `PRODUCTS_LIST_MAX_IMAGES` | `1` |  |

## Banderas por marca

Sobrescrituras por marca publicadas en Contentstack y guardadas en Mongo.

| Variable | Por defecto | Secreto |
|---|---|---|
| `BRAND_FLAGS_ENABLED` | `true` |  |
| `BRAND_FLAGS_CONTENT_TYPE` | `ms_clp_blp_flags` |  |
| `BRAND_FLAGS_LOCALE` | `es-mx` |  |
| `BRAND_FLAGS_COLLECTION` | `ms_clp_blp_configuration` |  |
| `BRAND_FLAGS_CACHE_TTL` | `3m` |  |
| `LANDING_BRANDS` | `LP,SB,WE,WS,PB,PBK,DPS,FB,DCK,GAP,BR,TRU,BRU` |  |
| `FF_OVERRIDE_JSON` | *(vacío)* |  |

## MongoDB

Solo para las banderas por marca. Vacío = sin Mongo; las banderas salen del YAML.

| Variable | Por defecto | Secreto |
|---|---|---|
| `MONGO_URI` | *(vacío)* | sí |
| `MONGO_SOCKET_TIMEOUT` | `2s` |  |
| `MONGO_SERVER_SELECTION_TIMEOUT` | `2s` |  |
| `MONGO_MAX_CONNECTION_IDLE_TIME` | `60s` |  |

## Vertex (ms_plp_search)

| Variable | Por defecto | Secreto |
|---|---|---|
| `VERTEX_BASE_URL` | `http://localhost:8084/api` |  |
| `VERTEX_SEARCH_PATH` | `/v2/search` |  |
| `VERTEX_USERNAME` | *(vacío)* | sí |
| `VERTEX_PASSWORD` | *(vacío)* | sí |
| `VERTEX_TIMEOUT` | `4s` |  |
| `VERTEX_DEFAULT_PAGE_SIZE` | `12` |  |

## Salesforce

| Variable | Por defecto | Secreto |
|---|---|---|
| `SALESFORCE_APPLICATION` | `Web` |  |
| `SALESFORCE_USER_ID_HEADER` | `x-sf-user-id` |  |
| `SALESFROCE_AUTH_USERNAME` | *(vacío)* | sí |
| `SALESFROCE_AUTH_PASSWORD` | *(vacío)* | sí |

## Salesforce (carruseles, platform-commons)

| Variable | Por defecto | Secreto |
|---|---|---|
| `SALESFORCE_CARROUSELS_URL` | `https://serviciosliverpoolsadecv.us-4.evergage.com/api2/authevent/liverpool` |  |
| `SALESFORCE_CARROUSELS_CONNECT_TIMEOUT_MS` | `1500` |  |
| `SALESFORCE_CARROUSELS_READ_TIMEOUT_MS` | `3000` |  |

## Salesforce V2

| Variable | Por defecto | Secreto |
|---|---|---|
| `SALESFORCE_V2_USER_ID_HEADER` | `x-sf-user-id` |  |
| `SALESFORCE_V2_DEVICE_ID_HEADER` | `x-sf-device-id` |  |

## Salesforce V2 (platform-commons)

| Variable | Por defecto | Secreto |
|---|---|---|
| `API_SALESFORCE_V2_URL` | *(vacío)* |  |
| `API_SALESFORCE_V2_CONNECT_TIMEOUT_MS` | `3000` |  |
| `API_SALESFORCE_V2_READ_TIMEOUT_MS` | `4000` |  |

## Jewel

| Variable | Por defecto | Secreto |
|---|---|---|
| `JEWEL_USER_ID_HEADER` | `x-jml-user-id` |  |
| `JEWEL_DEVICE_ID_HEADER` | `x-jml-device-id` |  |
| `JEWEL_MIN_ITEMS` | `3` |  |
| `JEWEL_MAX_ITEMS` | `15` |  |
| `ENABLE_JEWEL_CAROUSEL_ITEM_VARIANTS` | `true` |  |

## Jewel (platform-commons)

| Variable | Por defecto | Secreto |
|---|---|---|
| `API_JEWEL_URL` | *(vacío)* |  |
| `API_JEWEL_LP_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_SB_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_WS_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_WE_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_PB_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_PBK_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_GAP_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_BR_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_TRU_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_BRU_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_DPS_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_FB_INTEGRATION_ID` | *(vacío)* |  |
| `API_JEWEL_CONNECT_TIMEOUT_MS` | `3000` |  |
| `API_JEWEL_READ_TIMEOUT_MS` | `4000` |  |

## Indexador de categorías

Fuente de `categoryData`. De mejor esfuerzo.

| Variable | Por defecto | Secreto |
|---|---|---|
| `CATEGORY_INDEXER_URL` | `https://ogcp-apigke-site-d.liverpool.com.mx/category-tree-indexer/v1` |  |
| `CATEGORY_INDEXER_SEARCH_PATH` | `/search-category` |  |
| `CATEGORY_INDEXER_TIMEOUT` | `2s` |  |
| `CATEGORY_INDEXER_CACHE_TTL` | `24h` |  |
| `CATEGORY_INDEXER_CACHE_MAX_SIZE` | `5000` |  |

## store-service

Resuelve la cookie `favoriteStore`. De mejor esfuerzo.

| Variable | Por defecto | Secreto |
|---|---|---|
| `STORE_SERVICE_URL` | `https://ogcp-apigke-site-d.liverpool.com.mx/store-service/v1` |  |
| `STORE_SERVICE_TIMEOUT` | `2s` |  |
| `STORE_SERVICE_CACHE_TTL` | `24h` |  |
| `STORE_SERVICE_CACHE_MAX_SIZE` | `2000` |  |

## Lista negra (catalog-ingestion)

Productos que nunca salen en un carrusel. Falla abierta.

| Variable | Por defecto | Secreto |
|---|---|---|
| `BLACKLIST_BASE_URL` | `https://ogcp-apigke-site-d.liverpool.com.mx` |  |
| `BLACKLIST_PATH` | `/catalog-ingestion-service/v1/blacklist` |  |
| `BLACKLIST_LIMIT` | `-1` |  |
| `BLACKLIST_TIMEOUT` | `2s` |  |
| `BLACKLIST_CACHE_TTL` | `10m` |  |

## Servicio de autenticación

Intercambio de la cookie `SessionId`; ninguna ruta de landing lo llama hoy, se conserva de ms-home para los flujos que lean identidad.

| Variable | Por defecto | Secreto |
|---|---|---|
| `AUTH_BASE_URL` | `http://localhost:8085` |  |
| `AUTH_BASE_URL_LP` | *(vacío)* |  |
| `AUTH_BASE_URL_SB` | *(vacío)* |  |
| `AUTH_BASE_URL_WE` | *(vacío)* |  |
| `AUTH_BASE_URL_WS` | *(vacío)* |  |
| `AUTH_BASE_URL_PB` | *(vacío)* |  |
| `AUTH_BASE_URL_PBK` | *(vacío)* |  |
| `AUTH_BASE_URL_DPS` | *(vacío)* |  |
| `AUTH_BASE_URL_FB` | *(vacío)* |  |
| `AUTH_BASE_URL_DCK` | *(vacío)* |  |
| `AUTH_BASE_URL_GAP` | *(vacío)* |  |
| `AUTH_BASE_URL_BR` | *(vacío)* |  |
| `AUTH_BASE_URL_TRU` | *(vacío)* |  |
| `AUTH_BASE_URL_BRU` | *(vacío)* |  |
| `AUTH_EXCHANGE_TOKEN_PATH_TEMPLATE` | `/v{version}/auth/exchange-token` |  |
| `AUTH_DEFAULT_VERSION` | `2` |  |
| `AUTH_SUPPORTED_VERSIONS` | `2,3` |  |
| `AUTH_SESSION_COOKIE` | `SessionId` |  |
| `AUTH_CONNECT_TIMEOUT` | `2s` |  |
| `AUTH_READ_TIMEOUT` | `4s` |  |

## Akamai

Credencial EdgeGrid para Fast Purge. Vacía = la purga responde `CACHE_PURGE_NOT_CONFIGURED`.

| Variable | Por defecto | Secreto |
|---|---|---|
| `AKAMAI_HOST` | *(vacío)* |  |
| `AKAMAI_CLIENT_TOKEN` | *(vacío)* | sí |
| `AKAMAI_CLIENT_SECRET` | *(vacío)* | sí |
| `AKAMAI_ACCESS_TOKEN` | *(vacío)* | sí |
| `AKAMAI_CONNECT_TIMEOUT` | `2s` |  |
| `AKAMAI_READ_TIMEOUT` | `10s` |  |

## Purga de caché

| Variable | Por defecto | Secreto |
|---|---|---|
| `CACHE_PURGE_ENABLED` | `true` |  |
| `CACHE_PURGE_SITE_BASE_URL` | `https://ms-crp-clp-blp-service.liverpool.com.mx` |  |
| `CACHE_PURGE_PATHS` | *(vacío)* |  |
| `CACHE_PURGE_ALLOWED_HOSTS` | `ms-crp-clp-blp-service.liverpool.com.mx` |  |
| `CACHE_PURGE_TAGS` | `ms-clp-blp` |  |
| `CACHE_PURGE_ACTION` | `invalidate` |  |
| `CACHE_PURGE_NETWORK` | `production` |  |

## Webhook de purga

| Variable | Por defecto | Secreto |
|---|---|---|
| `CMS_PURGE_WEBHOOK_ENABLED` | `true` |  |
| `CMS_PURGE_WEBHOOK_CONTENT_TYPES` | `page_blp,page_clp,products_list,category_redirect` |  |
| `CMS_PURGE_WEBHOOK_ACTION` | `invalidate` |  |
| `CMS_PURGE_WEBHOOK_NETWORK` | `production` |  |
| `CMS_PURGE_WEBHOOK_COOLDOWN` | `10s` |  |

## Resiliencia

Un solo `CircuitBreakerRegistry` para todos los breakers. Sin reintentos.

| Variable | Por defecto | Secreto |
|---|---|---|
| `CB_FAILURE_RATE_THRESHOLD` | `50` |  |
| `CB_SLIDING_WINDOW_SIZE` | `50` |  |
| `CB_MIN_CALLS` | `20` |  |
| `CB_WAIT_OPEN` | `30s` |  |
| `CB_HALF_OPEN_CALLS` | `20` |  |

## Servidor y logging

| Variable | Por defecto | Secreto |
|---|---|---|
| `PORT` | `8080` |  |
| `SERVER_COMPRESSION_ENABLED` | `true` |  |
| `SERVER_COMPRESSION_MIN_RESPONSE_SIZE` | `2KB` |  |
| `SERVER_COMPRESSION_MIME_TYPES` | `application/json,application/problem+json` |  |
| `MAX_HTTP_HEADER_SIZE` | `16KB` |  |
| `LOG_LEVEL_APP` | `INFO` |  |
| `FEIGN_LOG_ENABLED` | `false` |  |
| `OUTBOUND_EXPECTED_FAILURES` | `auth:401,auth:404` |  |
| `MANAGEMENT_PORT` | `8081` |  |
| `LOG_FORMAT` | *(vacío)* |  |
| `LOG_COLOR` | `always` |  |
| `SWAGGER_ENABLED` | `true` |  |
