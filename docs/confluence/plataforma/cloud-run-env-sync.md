---
id: plataforma.cloud-run-env-sync
title: "Cloud Run Environment Sync"
type: how-to
status: current
owner: equipo-search-browse
lastReviewed: 2026-08-28
sources:
  - sync-cloud-run-env.sh
tags: [development, deployment, cloud-run, secrets, environment]
audience: [devops, sre, backend]
related:
  - desarrollo.md
  - configuracion.md
---

# Objetivo

Documentar `sync-cloud-run-env.sh`: qué hace, sus dos modos (`push` y `--pull`), y cómo administra
variables de entorno y secretos de un servicio de Cloud Run sin pasar por el pipeline de despliegue.

# Alcance

El script en sí — flags, atajos de ambiente, clasificación de variables, manejo de Secret Manager,
seguridad y códigos de salida. El catálogo completo de variables vive en
[Configuración](configuracion.md); cómo se empaqueta y despliega la imagen vive en
[Desarrollo y despliegue](desarrollo.md).

# Audiencia

DevOps, SRE y backend.

# Por qué existe

El pipeline de despliegue (`deploy_DEV_QA.yml` / `deploy_PROD.yml`, action
`google-github-actions/deploy-cloudrun@v2`) nunca trae `--set-env-vars`: la configuración de runtime
del servicio se administra fuera de banda, directamente en Cloud Run. Este script automatiza ese
paso en los dos sentidos:

- **push** (default) — lee un `.env` local y lo sincroniza contra un servicio de Cloud Run real,
  variable por variable.
- **pull** (`--pull`) — de solo lectura, nunca modifica Cloud Run. Lee el estado real del servicio y
  regenera un archivo tipo `.env.example.<ambiente>` (dev/qa/prod), incluida la referencia a Secret
  Manager de cada variable respaldada por un secreto — **nunca su valor**.

Crear una versión nueva de un secreto y apuntar Cloud Run a ella sigue siendo lo único que el script
hace por completo; **nunca lee** el valor de un secreto existente, a propósito — encaja con que los
desarrolladores solo tienen permiso para crear/rotar versiones de secretos, nunca para leerlas.

# Qué necesitas

| | |
|---|---|
| `gcloud` CLI | Instalado y autenticado (`gcloud auth login`) con permiso para leer y actualizar el servicio |
| `jq` | Dependencia del repo (la usa también `smoke-blocks.sh`) |
| Un `.env` local | Mismo formato que `.env.example` |

# Modo push (default)

```bash
sync-cloud-run-env.sh \
  --project crp-dev-dig-searchreplacement \
  --service dig-crp-clp-blp-service-dev \
  --region us-east4 \
  --env-file .env
```

`--project`, `--service` y `--region` también se pueden dar por variable de entorno
(`CLOUD_RUN_PROJECT`, `SERVICE`, `SERVICE_REGION` — los mismos nombres que ya usa el workflow de
despliegue). `--env-file` por defecto es `.env`. Ninguno tiene valor hardcodeado: sin los tres
primeros (o un atajo `--dev`/`--qa`/`--prod`), el script se detiene con un error explícito en vez de
asumir un proyecto.

## Atajos de ambiente

| Flag | Project | Service | Region |
|---|---|---|---|
| `--dev` | `crp-dev-dig-searchreplacement` | `dig-crp-clp-blp-service-dev` | `us-east4` |
| `--qa` | `crp-qas-dig-searchreplacement` | `dig-crp-clp-blp-service-qa` | `us-east4` |
| `--prod` | `crp-pro-dig-searchreplacement` | `dig-crp-clp-blp-service-prod` | `us-east4` |

Solo se puede dar uno (`--dev --qa` es un error de uso) y **nunca pisan** un
`--project`/`--service`/`--region` dado explícito — el atajo solo rellena lo que falte.

```bash
sync-cloud-run-env.sh --dev --env-file .env
sync-cloud-run-env.sh ... --dry-run   # ensayo: consulta el servicio, no escribe nada
sync-cloud-run-env.sh ... --yes       # no interactivo: confirma el default de cada prompt
```

## Cómo se clasifica cada variable

Usa `gcloud run services update --update-env-vars` (nunca `--set-env-vars`, que reemplaza todo el
set de variables del servicio): lo que ya está en Cloud Run y no está en el `.env` se queda intacto.
Toda variable —nueva o existente— pasa por una confirmación explícita antes de aplicarse:

| Estado | Significa | Opciones | Default (y lo que responde `--yes`) |
|---|---|---|---|
| `[NEW]` | No existe en el servicio | `p` la crea plana (default) / `s` la crea como secreto nuevo / `n` no la crea | `p` |
| `[UNCHANGED]` | Existe con el mismo valor | Ninguna: se salta sin preguntar — salvo que el nombre sugiera un secreto, en cuyo caso ofrece `m` (migrar a Secret Manager con ese mismo valor) | no hacer nada |
| `[EXISTS]` | Existe con otro valor | `y` sobrescribe (sigue plana) / `m` migra a Secret Manager con el valor de `.env` / `n` conserva | `n` |
| `[SECRET]` | Respaldada por Secret Manager (`valueFrom.secretKeyRef`) | `u` crea una versión nueva del secreto y apunta Cloud Run a ella / escribir `PLAINTEXT` sobrescribe (pasa a texto plano) / cualquier otra cosa conserva | conserva |

`--yes` responde el **default de cada fila**, no siempre "sí": bajo `--yes`, `[NEW]` se sigue
creando como plana pero nada más se mueve. **Ninguna opción que toque Secret Manager
(`s`/`m`/`u`/`PLAINTEXT`) se auto-confirma nunca con `--yes`**, sin importar el default — crear,
migrar o rotar un secreto siempre requiere una sesión interactiva. Un valor vacío en `.env`
deshabilita `s`/`m`/`u` para esa variable.

`[UNCHANGED]` (para una variable que no parece un secreto) es lo que hace que correr el script dos
veces con el mismo `.env` no produzca cambios. El `.env` se parsea a mano (sin `source`) para
soportar valores con espacios, `=`, comillas, comentarios, líneas `export KEY=value` y CRLF sin
romperse. Una `KEY` repetida en `.env` se detecta y se ignora en su segunda aparición, con aviso.

## Una revisión para las planas, una por secreto

Las variables **planas** confirmadas se juntan y se mandan al final **en una sola llamada** a
`--update-env-vars`, así que esa parte de la corrida queda en una sola revisión nueva del servicio —
no una por variable. Esa llamada final es todo o nada: si falla, ninguna de las variables planas
pendientes se aplica.

Las variables que **tocan Secret Manager** (crear un secreto nuevo, migrar una plana a un secreto, o
rotar la versión de uno existente) no entran en ese lote: cada una se aplica de inmediato, apenas se
confirma, con su propia llamada a Cloud Run. Una corrida que mezcla variables planas y secretos
puede terminar en **más de una revisión nueva** del servicio — una por cada secreto tocado, más una
para el lote de planas. No hace falta redesplegar: Cloud Run enruta tráfico nuevo a cada revisión
actualizada de inmediato.

# Crear, migrar y rotar secretos (`s` / `m` / `u`)

El script puede crear secretos nuevos y agregarles versiones, pero **nunca lee el valor de un
secreto** — no hay ningún llamado a `gcloud secrets versions access` (ni equivalente) en todo el
script. El valor de `.env` solo viaja por `stdin` hacia `gcloud secrets versions add
--data-file=-`, nunca como argumento de línea de comandos ni a un log.

- **Nombre del secreto**: por defecto, el mismo nombre que la variable — así ya existen los
  secretos reales de este proyecto (`WISHLIST_APIKEY`, `SALESFORCE_AUTHORIZATION_PROD`), nunca en
  minúsculas con guiones. El prompt lo muestra como default y deja escribir otro nombre — necesario
  para el sufijo `_PROD`/`_QA`/`_DEV` que ya usan algunos secretos, o para reusar uno compartido a
  propósito entre servicios.
- **Ya existe**: `gcloud secrets create` con un nombre que ya existe no es un error — el script
  sigue y le agrega una versión nueva al secreto existente.
- **Falla al crear/agregar versión**: se reporta esa variable como fallida (contador aparte del
  lote de planas) y la corrida sigue con la siguiente — no es todo o nada.
- **Versión huérfana**: si la versión se crea pero conectarla a Cloud Run falla, el script lo marca
  explícitamente en el resumen final y muestra el comando exacto (`gcloud run services update ...
  --update-secrets=...`) para completar la conexión a mano.
- **Replicación**: `gcloud secrets create` usa `--replication-policy=automatic` (réplica
  multi-región administrada por Google) al crear un secreto nuevo.

# Modo `--pull`: regenerar `.env.example.<ambiente>`

De solo lectura: nunca llama a `gcloud run services update` ni a nada de Secret Manager. Es la
forma de llevar registro de **qué secretos usa cada ambiente** sin mantenerlo a mano —
`.env.example.dev` / `.env.example.qa` / `.env.example.prod` se regeneran bajo demanda:

```bash
sync-cloud-run-env.sh --pull --dev    # equivale a --pull --dev --out .env.example.dev
sync-cloud-run-env.sh --pull --qa     # equivale a --pull --qa  --out .env.example.qa
sync-cloud-run-env.sh --pull --prod   # equivale a --pull --prod --out .env.example.prod
```

En este modo, `--env-file` pasa a ser la **plantilla** a recorrer (default `.env.example`, no
`.env`), y `--out` es obligatorio salvo que se use un atajo de ambiente. El script rechaza correr si
`--out` resuelve al mismo archivo que la plantilla, para que nunca se pise el `.env.example`
mantenido a mano.

Recorre la plantilla línea por línea preservando comentarios y estructura tal cual; solo reescribe
las líneas `KEY=valor` (y `export KEY=valor`) cuyo nombre existe como variable válida:

| Estado real en Cloud Run | Qué se escribe en `--out` |
|---|---|
| Variable plana | `KEY=<valor real>` |
| Respaldada por Secret Manager | `KEY=` (**nunca el valor**), con un comentario arriba: `# [Secret Manager] <secreto>:<versión>` |
| No configurada en el servicio | La línea de la plantilla, sin tocar, con `# [Cloud Run] not set` arriba |

**Red de seguridad:** no todo lo que debería ser secreto está migrado a Secret Manager todavía. Si
una variable sigue siendo texto plano en Cloud Run pero su nombre sugiere un secreto (mismo
criterio que abajo), su valor **no se escribe** — se deja en blanco con un aviso, tanto en el
archivo generado como en la terminal — para que `--pull` nunca sea la forma más fácil de filtrar un
secreto sin migrar hacia un archivo pensado para commitearse.

# Seguridad

- **Nunca imprime ni loguea el contenido de los valores** — ni en la salida normal, ni en
  `--dry-run`, ni en un error de `gcloud`. Solo se muestran nombres de variable y su estado.
- Usa la sesión de `gcloud` ya autenticada del desarrollador: no crea ni pide ningún archivo de
  credenciales nuevo.
- Siempre pasa `--project` explícito a cada llamada de `gcloud` — nunca depende de
  `gcloud config set project`.
- Una variable cuyo nombre termina en `_TOKEN`, `_PASSWORD`, `_SECRET`, `_KEY`, `_APIKEY`,
  `_AUTHORIZATION` o `_URI` (mismo criterio que usa `.env.example` para marcar `SECRET` inline)
  imprime un aviso sugiriendo Secret Manager en vez de un env var plano — es solo un aviso, no
  bloquea ni cambia el destino.
- Una variable respaldada por Secret Manager nunca se sobrescribe en silencio: hace falta escribir
  la palabra `PLAINTEXT` a mano — `--yes` nunca la auto-confirma.
- `--pull` es de solo lectura: solo lee el nombre y la versión del secreto que respalda cada
  variable (`valueFrom.secretKeyRef`), jamás su valor.

# Códigos de salida

| Código | Significado |
|---|---|
| `0` | Éxito — todo lo confirmado se aplicó (o `--dry-run`/`--pull` terminaron sin errores) |
| `1` | La actualización final de variables planas falló (todo o nada), y/o al menos una operación de Secret Manager (`s`/`m`/`u`) falló — cada una independiente, ver el resumen final para el detalle |
| `2` | Error de uso o de entorno: `gcloud`/`jq` ausente, sin autenticación, `.env`/`--out` inválidos, o proyecto/servicio/región inválidos |

# Problemas conocidos

| Síntoma | Causa y arreglo |
|---|---|
| `gcloud: command not found` | Instala el SDK de Google Cloud y corre `gcloud auth login` |
| `No hay ninguna cuenta autenticada en gcloud` | `gcloud auth login` |
| `No se pudo leer el servicio '...'` | Revisa que `--project`, `--service` y `--region` sean correctos y que la cuenta tenga permiso de lectura sobre Cloud Run en ese proyecto |
| La actualización final de variables planas falla | Es todo o nada: ninguna de las variables planas pendientes se aplica. Se listan bajo "Fallidas"; vuelve a correr el script para reintentar |
| Corriendo en un entorno sin terminal (CI, `nohup`) | Usa `--yes`; sin él, cualquier prompt sin `/dev/tty` cae al valor por defecto de la tabla de clasificación. Ninguna acción de Secret Manager se dispara así de todas formas |
| `--pull` sin `--out` | `--out PATH` es obligatorio en modo `--pull` (salvo atajo `--dev`/`--qa`/`--prod`) |
| `--pull` dice "--out no puede ser el mismo archivo que la plantilla" | `--out` y `--env-file` resolvieron a la misma ruta; usa un archivo de salida distinto |
| Falla crear/actualizar un secreto (`s`/`m`/`u`) | Independiente por variable, no todo-o-nada: se reporta esa variable como fallida y la corrida sigue |
| "Se creó la versión N ... pero no se pudo conectar Cloud Run" | La versión quedó huérfana (existe, no conectada). El script imprime el comando exacto para completar la conexión a mano. Causa común: la identidad que corre el script no tiene permiso para que Cloud Run otorgue `roles/secretmanager.secretAccessor` sobre el secreto nuevo |

Referencia completa de uso (todas las banderas y el detalle de verificación del script):
`scripts/README.md`.
