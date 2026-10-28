---
id: home
title: "ms-clp-blp"
type: overview
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-27
sources:
  - src/main/java/com/liverpool/ms_clp_blp/
tags: [ms-clp-blp, blp, clp, overview]
audience: [frontend, backend, qa, soporte, sre]
summary: >
  Microservicio Java 21 / Spring Boot 4.1 que compone las páginas de aterrizaje de marca (BLP) y de
  categoría (CLP) del e-commerce de Liverpool a partir de Contentstack. Reemplaza al endpoint
  `/content/page-blp|page-clp` de web-bff y sigue la arquitectura de ms-home.
---

# Qué hace

- **`GET /blp/{locale}/**`** y **`GET /clp/{locale}/**`** — leen de Contentstack la entrada
  `page_blp`/`page_clp` cuyo `category_id` es el último segmento de la URL, la componen (bloques
  estáticos tal cual, carruseles `products_list` resueltos o anunciados como *placeholder*) y le
  agregan `categoryData` (árbol del indexador de categorías + redirect del CMS).
- **`POST /blp/blocks/products-list`** y **`POST /clp/blocks/products-list`** — resuelven en lote los
  carruseles que la página entregó *lazy*, contra Vertex, Salesforce, Salesforce V2 o Jewel según su
  `source_of_data`, quitando los productos de la lista negra.
- **`POST /ops/cache-purge**`** y **`POST /webhooks/contentstack/cache-purge`** — invalidan el edge de
  Akamai.
- **`POST /webhooks/contentstack`** — recibe las banderas por marca publicadas en el CMS (`ms_clp_blp_flags`).

# Qué **no** hace

- No devuelve `globalData`: el header, footer, banderas y temas globales salen de
  `GET /global-data` de ms-home.
- No intercambia la cookie `SessionId` con el servicio de autenticación: la audiencia sale de
  `x-is-logged-in`.

# Por dónde empezar

| Quiero… | Página |
|---|---|
| Entender o soportar la página BLP/CLP | [Páginas BLP y CLP](funcionalidades/paginas-blp-clp.md) |
| Entender o soportar un carrusel | [Carruseles de productos](funcionalidades/carruseles-de-productos.md) |
| Saber dónde va una clase nueva | [Arquitectura](plataforma/arquitectura.md) |
| Ver todas las variables de entorno | [Configuración](plataforma/configuracion.md) |
| Levantarlo local, probar, desplegar | [Desarrollo y despliegue](plataforma/desarrollo.md) |
| Saber por qué se decidió algo | [ADRs](reference/adrs.md) |
