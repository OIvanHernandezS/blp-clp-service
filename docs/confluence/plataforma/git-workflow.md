---
id: plataforma.git-workflow
title: "Git Workflow"
type: how-to
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-27
sources:
  - .github/workflows/tag-auto-develop.yml
  - .github/workflows/tag-manual.yml
  - .github/workflows/main-branch-guard.yml
  - .github/workflows/deploy_DEV_QA.yml
  - .github/workflows/deploy_PROD.yml
  - .githooks/commit-msg
  - Dockerfile
tags: [development, git, release, tagging, ci-cd]
audience: [backend, devops]
related:
  - desarrollo.md
---

# Objetivo

Describir el modelo de ramas y el etiquetado semántico de ms-home: qué rama usar para cada tipo
de trabajo y cómo se generan las etiquetas (tags) de versión.

# Alcance

Ramas `main`/`develop`, ramas de épica (features grandes/experimentales), ramas `hotfix/*`, y los
tres workflows de GitHub Actions que automatizan el etiquetado y protegen `main`.

# Audiencia

Backend y DevOps.

# Modelo de ramas

| Rama | Propósito | Se crea desde | Se fusiona hacia |
|---|---|---|---|
| `main` | Código en Producción. Sin commits directos. | — | — |
| `develop` | Rama de integración continua. | `main` (una sola vez) | — |
| Rama de épica (por ejemplo `vertex`) | Features grandes o experimentales que tardan varios días. | `develop` | `develop`, vía PR, cuando pasa validación |
| Sub-features de una épica (por ejemplo `feat/vertex-1`) | Trabajo granular dentro de una épica. | La rama de épica | La rama de épica |
| `hotfix/*` | Corrección de un bug detectado en Producción. | `main` | `main` y de vuelta a `develop` |

Una rama de épica debe traer `develop` todos los días (`git pull origin develop`) mientras esté
abierta, para minimizar conflictos de fusión antes de abrir el PR final.

## Flujo de hotfix

1. Se detecta un bug en Producción → se crea `hotfix/<nombre>` desde `main`.
2. Ya resuelto, se dispara el workflow manual de etiquetado (`tag-manual.yml`, ver abajo) para
   generar el tag que valida el fix en `qa`/`prod`.
3. Se fusiona `hotfix/<nombre>` en `main` (una de las dos vías permitidas de merge directo a
   `main`, ver [Guardia de `main`](#guardia-de-main)).
4. Se sincroniza el fix de vuelta a `develop` ("bajar hotfix") para que no se pierda en el próximo
   release. Hoy este paso es manual — ver [Pendientes](#pendientes).

## Flujo de rama de épica (validar antes de fusionar)

Una épica no espera al PR final para probarse en `qa`: se etiqueta ella misma, sobre su propio
commit, todas las veces que haga falta durante los días que esté abierta.

1. Con la épica lista para una ronda de validación, se dispara `tag-manual.yml` con `ref: <rama de
   la épica>` (por ejemplo `vertex`) y un `custom_tag` de cuatro componentes sobre la última versión
   de `develop` (`v2.0.1.1`, `v2.0.1.2`, …) — el mismo formato de cuatro componentes, con la misma
   advertencia del workflow, que ya usa un hotfix (`v1.0.3.1`); ver la sección **Etiquetado manual**
   (`tag-manual.yml`) más abajo.
2. Ese tag se despliega a `qa` como cualquier otro (`deploy_DEV_QA.yml`, ver la sección
   **Etiquetado y despliegue** más abajo) y alguien valida el resultado a mano — no hay un gate
   automático que decida "funciona".
3. **Si no funciona:** se sigue iterando en la rama de la épica (más commits, sub-features vía
   `feat/<épica>-N` fusionadas de vuelta a la épica) y se repite el paso 1 con el siguiente
   componente de parche.
4. **Si funciona:** la épica se fusiona a `develop` vía PR (la vía ya descrita en
   [Modelo de ramas](#modelo-de-ramas)). Ese push a `develop` dispara `tag-auto-develop.yml`
   normalmente y produce el siguiente tag real de tres componentes (`v2.1.0` si la épica trae al
   menos un `feat:`). Los tags de cuatro componentes generados en el paso 1 no vuelven a usarse — son
   desechables, solo existieron para validar en `qa`.
5. Si la épica va a seguir recibiendo trabajo grande después de fusionarse (una segunda ronda de
   features grandes sobre lo que ya se integró), se abre una rama de épica nueva desde `develop` en
   el tag recién creado (por ejemplo `vertex-2` desde `v2.1.0`) — el mismo patrón con el que se creó
   la rama de épica original (ver [Modelo de ramas](#modelo-de-ramas)), no una continuación de la
   rama vieja.

Una rama de épica larga debe traer `develop` todos los días (`git pull origin develop`, ya indicado
en [Modelo de ramas](#modelo-de-ramas)) para que el tag de validación de cada ronda no arrastre una
divergencia grande frente a lo que de verdad se va a fusionar.

# Etiquetado automático — `tag-auto-develop.yml`

Se dispara con cada `push` a `develop` (es decir, con cada PR fusionado). Calcula la siguiente
versión semántica y crea el tag `vMAJOR.MINOR.PATCH`:

1. Busca el último tag `vX.Y.Z` alcanzable (`v0.0.0` si no hay ninguno todavía).
2. Revisa los mensajes de commit desde ese tag usando el mismo patrón de Conventional Commits que
   exige `.githooks/commit-msg`: un `!` después del tipo/scope o un pie `BREAKING CHANGE` implica
   **major**; cualquier `feat:` implica **minor**; cualquier otro tipo (`fix`, `refactor`, `perf`,
   etc.) implica **patch**.
3. Crea el tag anotado y lo empuja a `origin`.

Si `HEAD` ya tiene un tag `vX.Y.Z`, el workflow no hace nada (evita tags duplicados en re-runs).

# Etiquetado manual — `tag-manual.yml`

Se dispara a mano desde la pestaña Actions (`workflow_dispatch`), para tags de `prod`, `qa`, o de
una rama `hotfix/*`. Entradas:

| Entrada | Descripción |
|---|---|
| `ref` | Rama a etiquetar. El workflow la describe como `main` o `hotfix/<nombre>`, pero es un campo de texto libre sin restricción — en la práctica también se usa para etiquetar la rama de una épica en validación (ver la sección **Flujo de rama de épica** más arriba). Por defecto `main`. |
| `environment` | Etiqueta informativa (`prod` o `qa`) que queda en el mensaje del tag y en la notificación. **No selecciona ningún destino de despliegue.** |
| `bump` | `patch`, `minor`, `major` o `none`. Se ignora si se define `custom_tag`. |
| `custom_tag` | Tag explícito, por ejemplo `v1.0.4-hotfix.1`. Tiene prioridad sobre `bump`. |

Se recomienda un sufijo de pre-release (`-rc.X`, `-hotfix.X`) en vez de un tag de cuatro
componentes (`v1.0.3.1`); el workflow deja pasar ambos formatos pero emite una advertencia cuando
detecta cuatro componentes.

# Guardia de `main`

`main-branch-guard.yml` corre en cada Pull Request contra `main` y falla el check si la rama de
origen del PR no es `develop` ni empieza con `hotfix/`. Esas son las dos únicas vías saneadas
hacia `main`: promoción regular vía `develop` y parche urgente vía `hotfix/*`. Así se evita que una
rama de feature o una épica se fusione directo a `main` sin pasar por `develop` primero.

Este check por sí solo no bloquea el botón de merge — falta marcarlo como *required status
check* en la configuración de protección de rama de `main` (ver [Pendientes](#pendientes)).

# Etiquetado y despliegue: el tag es la puerta de entrada

Los tres workflows de este documento (`tag-auto-develop.yml`, `tag-manual.yml`,
`main-branch-guard.yml`) siguen sin construir imagen ni tocar Cloud Run — solo crean y empujan un
tag de git, o bloquean un PR. El despliegue real sigue viviendo en los workflows descritos en
[Desarrollo y despliegue](desarrollo.md) (`deploy_DEV_QA.yml`/`deploy_PROD.yml`), pero ya no son un mecanismo
paralelo sin conexión: **los tres entornos (`dev`, `qa`, `prod`) se disparan a mano
(`workflow_dispatch`) eligiendo como *ref* uno de los tags que este documento genera, nunca una
rama**, y un paso "Enforce tag-based deploy" falla el job de inmediato
(`github.ref_type != 'tag'`) si alguien intenta correrlo sobre una rama. Un push directo a
`deploy_DEV`/`deploy_QA`/`deploy_PROD` ya no dispara nada — esas ramas quedaron sin uso.

Dentro de `deploy_DEV_QA.yml`, el input `environment` (`develop` | `qa`) decide a cuál de los dos
Cloud Run se despliega el tag elegido; `deploy_PROD.yml` no pregunta nada más, siempre es
`production`. Esto significa que hoy **`dev` no se despliega solo al fusionar a `develop`** —
también requiere elegir a mano un tag y disparar el workflow, igual que `qa`. La única diferencia
real entre entornos es cuál tag es razonable elegir para cada uno (ver la tabla de abajo).

`APP_VERSION` viaja del tag elegido en el dispatch (`github.ref_name`) al `--build-arg` de
`docker build`, y de ahí al `ARG`/`ENV` del `Dockerfile` y a la cabecera `x-app-version` de toda
respuesta (heredado de ms-home) — es la forma de confirmar, ya desplegado, qué
tag corrió realmente en un ambiente dado. La imagen en Artifact Registry se sigue etiquetando por
`github.sha`, no por el tag semántico: son dos identificadores del mismo build que conviven a
propósito, uno para localizar la imagen en el registro y otro para que un humano lea la versión en
una respuesta HTTP.

## Mapeo de entornos

| Entorno | Se despliega vía |
|---|---|
| `dev` | `deploy_DEV_QA.yml` (`environment: develop`), disparado a mano sobre un tag — normalmente el último `vMAJOR.MINOR.PATCH` de `tag-auto-develop.yml` |
| `qa` | Lo mismo (`environment: qa`), sobre un tag manual (`tag-manual.yml`) o el tag de validación de una rama de épica — ver la sección **Flujo de rama de épica** más arriba |
| `prod` | `deploy_PROD.yml`, sobre un tag aprobado desde `main` o desde un `hotfix/*` ya validado en `qa` |

Esta tabla ya describe el mecanismo real, no solo la intención — ver
[Pendientes](#pendientes) para lo que todavía falta alrededor de este flujo.

# Pendientes

- Marcar `main-branch-guard.yml` como *required status check* en la protección de rama de `main`
  (acción de administrador del repositorio en GitHub, no se puede hacer desde un workflow).
- Agregar un `CODEOWNERS` — no existe todavía en el repositorio.
- Automatizar "bajar hotfix": abrir un PR de `hotfix/*` hacia `develop` en cuanto el hotfix se
  fusiona a `main`, para que la sincronización no dependa de que alguien se acuerde de hacerla a
  mano.
- Configurar reglas de protección de tags en GitHub para impedir que se borren o se muevan tags
  de release ya creados.
