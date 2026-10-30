---
id: reference.adrs
title: "ADRs"
type: adr
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-29
sources:
  - src/main/java/com/liverpool/ms_clp_blp/
tags: [adr, decisiones]
summary: >
  Decisiones de diseño de ms-clp-blp. Las decisiones heredadas de ms-home (arquitectura por
  funcionalidad, Contentstack directo, resolución eager/lazy, circuit breakers sin reintentos) aplican
  igual y están documentadas en el espacio de ms-home.
---

# Decisiones

## ADR-001 — La página se busca por `category_id` directo en Contentstack {#adr-001}

**Estado:** aceptada (2026-10-27)  
**Contexto:** web-bff pedía la página al content-service, que buscaba `page_blp`/`page_clp` por
`category_id` = último segmento de la URL. ms-home ya lee Contentstack directo (sin content-service).  
**Decisión:** leer Contentstack directo, con el mismo cliente, breaker por marca y caché Caffeine de
ms-home, buscando por `category_id` (tal cual y en minúsculas) y con el filtro `taxonomies.brand` en
stacks compartidos.  
**Consecuencias:** un salto menos y el mismo comportamiento que ms-home ante fallas; el slug de la URL
sigue siendo irrelevante, como en web-bff.

## ADR-002 — La respuesta no incluye `globalData` {#adr-002}

**Estado:** aceptada (2026-10-27)  
**Contexto:** web-bff incrustaba la entrada `global` completa (~72 KB de 200 KB) en cada BLP/CLP.  
**Decisión:** no incluirla; el frontend la toma de `GET /global-data` de ms-home, que ya la cachea.  
**Consecuencias:** respuesta ~35 % más chica y una sola caché de la entrada global. Los `header`/`footer`
propios de la página (sobrescrituras opcionales) **sí** se devuelven.

## ADR-003 — `categoryData` es de mejor esfuerzo también en CLP {#adr-003}

**Estado:** aceptada (2026-10-27)  
**Contexto:** en web-bff, si el indexador fallaba, una BLP salía sin `categoryData` pero una CLP
respondía 404.  
**Decisión:** las dos páginas salen sin `categoryData` cuando el indexador o el redirect fallan.  
**Consecuencias:** una caída del indexador ya no convierte las CLP en páginas inexistentes. Solo se
cachean respuestas exitosas del indexador (24 h).

## ADR-004 — La lista negra se lee de catalog-ingestion {#adr-004}

**Estado:** aceptada (2026-10-27)  
**Contexto:** web-bff y platform-commons 1.0.1 leen la lista negra del search-facade, que se está
deprecando; ms_pdp_search y platform-commons (rama `feat/AHDV-401`) pasan a
`GET /catalog-ingestion-service/v1/blacklist/{site}?limit=-1`.  
**Decisión:** usar catalog-ingestion con un cliente propio (ruta y sitios configurables), detrás de la
bandera `carousel-restricted-products`, fallando abierto, aplicado en `ProductsListUseCases` después de
`min_of_products`.  
**Consecuencias:** el carrusel en línea y el del lote filtran igual. Mientras catalog-ingestion no esté
en un ambiente, los carruseles salen sin filtrar (registrado como `RESTRICTED_PRODUCTS_UNAVAILABLE`).

## ADR-005 — La tienda favorita sale solo de la cookie, y vuelve personal la página {#adr-005}

**Estado:** aceptada (2026-10-27)  
**Contexto:** web-bff tomaba la tienda de la cookie `favoriteStore` para invitados y del
profile-service para logueados, y la usaba solo en `customData` de analítica (`selected_store.*`).  
**Decisión:** usar solo la cookie (sin profile-service), resuelta contra store-service con caché de
24 h. Una petición con la cookie responde `Cache-Control: no-store, private`.  
**Consecuencias:** un logueado sin la cookie no tiene tienda en la analítica. Como la respuesta lleva
la tienda de ese usuario, no puede compartirse en el edge: solo la página sin cookie es cacheable.
Si se quiere cachear también con tienda, el edge debe incluir la cookie en la llave de caché.

## ADR-006 — Sin contenedores anidados (`tabs_container`) {#adr-006}

**Estado:** aceptada (2026-10-27)  
**Contexto:** ms-home resuelve bloques dentro de `tabs_container`; los esquemas `page_blp`/`page_clp`
no permiten ese bloque.  
**Decisión:** no portar ese mecanismo ni sus banderas; un `tabs_container` se serviría como contenido
estático.  
**Consecuencias:** las únicas banderas son las de los proveedores de carrusel y las dos de todo
carrusel. Si el CMS habilita tabs en una landing, se porta el mecanismo de ms-home.

## ADR-007 — Sin intercambio de `SessionId` con el servicio de autenticación {#adr-007}

**Estado:** aceptada (2026-10-29)  
**Contexto:** web-bff intercambia la cookie `SessionId` con el servicio de autenticación en cada
petición y usa el `isLoggedIn` resultante para `audience_filter` y para Salesforce. ms-clp-blp heredó
de ms-home el cliente y las variables `AUTH_*`, pero ninguna ruta lo llamaba: la página toma el
logueado/invitado de `x-is-logged-in` y Salesforce V1 toma el id de `x-sf-user-id` cuando viaja una
`SessionId` bien formada.  
**Decisión:** quitar el cliente de autenticación, el intercambio, `profileId`/`accessToken` de
`SessionContext` y las variables `AUTH_*`. La cookie `SessionId` solo se valida por formato, sin
llamadas.  
**Consecuencias:** cero llamadas a autenticación y 20 variables menos. El servicio no tiene identidad
verificada del usuario: si un bloque llega a necesitarla, hay que reintroducir el intercambio. Los
headers `x-auth-version`/`x-client-platform` siguen permitidos en CORS porque el navegador aún los
manda.
