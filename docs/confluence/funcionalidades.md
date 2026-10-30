---
id: funcionalidades
title: "Funcionalidades"
type: overview
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-29
sources:
  - src/main/java/com/liverpool/ms_clp_blp/landing/
tags: [funcionalidades, index, curl]
audience: [frontend, backend, qa, soporte, sre]
summary: >
  Una página por cada cosa que hace ms-clp-blp. Cada una se lee de arriba abajo y trae todo lo
  necesario para implementarla **y** para soportarla en producción: configuración, dónde mirar,
  qué hacer si falla y los cURL.
---

# Cómo leer estas páginas

Todas las páginas de funcionalidad tienen las mismas seis secciones, siempre en este orden:

| Sección | Qué responde | Para quién |
|---|---|---|
| **En 30 segundos** | Qué es y qué sistemas toca | Todos |
| **Cómo funciona** | El flujo, con su diagrama | Frontend, backend, QA |
| **Implementación** | Cómo se configura en el CMS, el contrato y las reglas | Frontend, backend, editores |
| **Configuración** | Cada variable o bandera: dónde vive, qué valor tiene y qué hace | Backend, soporte |
| **Soporte** | Dónde mirar en producción, síntomas y acciones, cURLs, escalamiento | Soporte, SRE |
| **Referencias** | Las decisiones (ADR) y el código del que sale la página | Backend |

# Cómo usar los cURL

Los cURL de cada página asumen estas variables. Se declaran **una vez** por terminal:

```bash
# Dónde está el servicio
export MS_CLP_BLP_URL="http://localhost:8080"   # local; el host de Cloud Run/Akamai cuando exista

# Credencial Basic de entrada: todo endpoint la exige; sin ella responde 401 UNAUTHORIZED.
# Son MS_CLP_BLP_SECURITY_USERNAME / MS_CLP_BLP_SECURITY_PASSWORD del servicio en Cloud Run.
export MS_CLP_BLP_USER="<usuario>"
export MS_CLP_BLP_PASS="<contraseña>"

# Tienda favorita (cookie favoriteStore). Sin definir = sin tienda.
export MS_CLP_BLP_STORE="7"

# Cookie SessionId de una sesión real (<uuid>.<firma>). Solo se valida su formato: habilita los
# carruseles de Salesforce V1 y el esqueleto cacheable del logueado. Es un secreto: no la pegues en
# tickets ni en chats.
export MS_CLP_BLP_SESSION_ID="<SessionId>"
```

Para no repetir la credencial en cada comando, esta terminal (y solo esta) la toma de un `.curlrc`
temporal:

```bash
export CURL_HOME="$(mktemp -d)"
printf 'user = "%s:%s"\n' "$MS_CLP_BLP_USER" "$MS_CLP_BLP_PASS" > "$CURL_HOME/.curlrc"
```

Lo que cambia el resultado de cualquier cURL:

- **`x-brand-id`** elige la marca (`LP`, `SB`, …) y con ella el stack de Contentstack. Sin él se usa
  `CMS_BRAND` (por defecto `LP`).
- **`x-is-logged-in`** declara la audiencia: `true` filtra para usuario logueado, `false` o ausente
  para invitado. Decide qué bloques llegan y si la página se puede cachear en el edge.
- **`x-channel`** decide si un bloque se lee con `enable_on_web` (`WEB`, `KIOSCO`, `POS`, ausente) o
  con `enable_on_apps` (`APP`).
- Toda respuesta trae **`x-request-id`**: es el valor a buscar en los logs para ver esa llamada.

> [!WARNING]
> Nunca pegues en un ticket o en Confluence el valor real de la credencial Basic ni de ningún token:
> son secretos.
