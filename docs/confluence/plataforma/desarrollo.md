---
id: plataforma.desarrollo
title: "Desarrollo y despliegue"
type: how-to
status: current
owner: equipo-search-browse
lastReviewed: 2026-10-27
sources:
  - pom.xml
  - Dockerfile
  - .githooks/
  - .github/workflows/
  - src/test/java/com/liverpool/ms_clp_blp/
tags: [desarrollo, pruebas, despliegue, cloud-run]
audience: [backend, devops]
summary: >
  Cómo levantar el servicio en local, qué valida el build antes de cada push, y cómo se construye y
  despliega la imagen en Cloud Run.
---

# Requisitos

- **JDK 21.**
- **Acceso al repositorio Maven privado** de `com.liverpool:platform-commons`
  (`https://us-east4-maven.pkg.dev/sit-cicd-images-apps/liverpool-platform-commons-libraries`) en tu
  `~/.m2/settings.xml`.
- No hace falta base de datos local: las banderas por marca están apagadas en el perfil `dev`.

# Levantarlo en local

```bash
cp .env.example .env               # credencial local + valores por defecto
# completa CONTENT_STACK_LP_API_KEY / CONTENT_STACK_LP_DELIVERY_TOKEN (sin ellas, 502)
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

- API: `http://localhost:8080` · Actuator: `http://localhost:8081/actuator/health` ·
  Swagger: `http://localhost:8080/swagger-ui.html`.
- Prueba: `curl -s -u local-user:local-pass -H "x-brand-id: LP" http://localhost:8080/blp/es-mx/tienda/esteelauder/catst26617625 | jq '.blocks | length'`.

# Pruebas

| Comando | Qué corre |
|---|---|
| `./mvnw test` | Toda la suite (JUnit 5, Mockito, AssertJ, MockMvc, WireMock) |
| `./mvnw test -Dtest=ArchitectureTest` | Solo las reglas de arquitectura |
| `./mvnw verify` | Suite + JaCoCo (≥ 85 % de líneas) + `spotless:check` |

Convenciones: el árbol de pruebas refleja el de `main`; `<Clase>Test` para unitarias, `*WireTest`
para un cliente Feign contra WireMock, `*EndToEndFeignTest` para el stack completo.
`LandingEndToEndFeignTest` compone la entrada real de `page_blp` capturada de producción
(`src/test/resources/fixtures/page-blp-catst26617625.json`) contra stubs de todos los sistemas.

# Git hooks

Los hooks se instalan solos en el primer `./mvnw` (`git-build-hook-maven-plugin` apunta
`core.hooksPath` a `.githooks/`):

| Hook | Qué hace |
|---|---|
| `commit-msg` | Exige Conventional Commits (`feat:`, `fix:`, `docs:`…) |
| `pre-commit` | Bloquea archivos `.env*`, aplica Spotless a los `.java` en stage |
| `pre-push` | `./mvnw -q verify` si el push toca `.java` o `pom.xml` |

Para saltarlos en una emergencia: `SKIP_HOOKS=1` o `--no-verify`.

# Imagen y despliegue

- `./mvnw clean package` → `target/ms-clp-blp-0.0.1-SNAPSHOT.jar`;
  `docker build --build-arg ENVIRONMENT=dev -t ms-clp-blp .`
- El Dockerfile agrega el agente de OpenTelemetry y traduce `ENVIRONMENT` a
  `-Dspring.profiles.active`. Puertos: 8080 (API) y 8081 (actuator).
- `deploy_DEV_QA.yml` (dispatch `develop`/`qa`) y `deploy_PROD.yml` construyen desde un **tag**,
  publican en `us-docker.pkg.dev/sit-cicd-images-apps/digital-crp-clp-blp-service/<SERVICE>` y
  despliegan en Cloud Run (`us-east4`). El nombre del servicio y el proyecto salen de las variables
  del environment de GitHub (`vars.SERVICE`, `vars.CLOUD_RUN_PROJECT`).
- El pipeline **no** escribe variables de entorno en Cloud Run: eso se hace con
  [Cloud Run Env Sync](cloud-run-env-sync.md).

> [!IMPORTANT]
> Antes del primer despliegue hay que crear en GitHub los environments `development`, `qa` y
> `production` con sus variables y secretos, el repositorio de Artifact Registry
> `digital-crp-clp-blp-service` y el secreto `GOOGLE_CHAT_CLP_BLP_SERVICE` para las notificaciones.
