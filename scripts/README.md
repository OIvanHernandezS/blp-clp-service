# scripts/

Utilidades de desarrollo de ms-clp-blp. No forman parte del artefacto desplegable.

- **`publish_confluence.sh` / `publish_confluence.py`** — publican [`docs/confluence/`](../docs/confluence/)
  en Confluence Cloud. Documentado abajo.
- **`sync-cloud-run-env.sh`** — sincroniza un `.env` local contra las variables de entorno de un
  servicio de Cloud Run, sin pisar lo que no está en el `.env`, y puede además crear secretos nuevos,
  migrar una variable plana a Secret Manager, o rotar la versión de un secreto existente (nunca lee un
  valor). En sentido inverso (`--pull`), regenera un archivo `.env.example.<ambiente>` desde el estado
  real del servicio, incluida la referencia a Secret Manager de cada secreto (nunca su valor).
  Documentado abajo.

---

# Publicar la documentación en Confluence

Sube las páginas de [`docs/confluence/`](../docs/confluence/) al espacio de Confluence de ms-clp-blp
(clave pendiente — `TODO_CLP_BLP_SPACE` hasta que el espacio exista) de `liverpooldigital.atlassian.net`. El árbol de páginas y sus títulos salen
de [`docs/confluence/confluence-manifest.yml`](../docs/confluence/confluence-manifest.yml).

El repositorio es la fuente de verdad **de las páginas que nadie ha tocado en Confluence**. Se edita
el Markdown, se corre el script, y Confluence refleja el cambio. Pero Confluence también se puede
editar: el publicador sella en cada página lo que escribió, así que en la corrida siguiente distingue
lo suyo de lo que editó una persona y **conserva lo segundo** en vez de pisarlo. Eso es lo que
permite corregir un párrafo, o pegar la imagen de un diagrama, sin pasar por el repositorio.

## Qué necesitas

| | |
|---|---|
| Python 3 | Ya viene en macOS. El wrapper crea su propio virtualenv en `.venv-confluence/` |
| Dependencias | Se instalan solas desde [`requirements.txt`](requirements.txt) — PyYAML, Markdown, certifi |
| Node.js (`npx`) | Solo para publicar páginas con diagramas: renderiza el mermaid a PNG. La primera vez descarga `@mermaid-js/mermaid-cli` y un Chromium (unos minutos) |
| Token de API | [id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens) → *Create API token* |
| Permiso en el espacio | Tu cuenta debe poder crear páginas en `TODO_CLP_BLP_SPACE` |

No hay que instalar nada a mano: el wrapper prepara el entorno en la primera corrida.

## Configuración (una vez)

```bash
cp scripts/.env.confluence.example scripts/.env.confluence
```

Rellena **dos valores**; el resto ya viene apuntando al espacio correcto:

```bash
CONFLUENCE_EMAIL=tu.correo@liverpool.com.mx   # tu cuenta de Atlassian, no un alias
CONFLUENCE_API_TOKEN=ATATT3...                # el token, NO tu contraseña
```

El archivo con credenciales nunca se commitea: lo cubren `.gitignore` y el hook de pre-commit.
También se acepta `scripts/.env`, o cualquier ruta vía `CONFLUENCE_ENV_FILE`.

## Uso

```bash
scripts/publish_confluence.sh --dry-run   # ensayo: valida el manifiesto, no toca la red
scripts/publish_confluence.sh --status    # qué haría con cada página, sin escribir nada
scripts/publish_confluence.sh             # publica
```

Desde dentro de `scripts/`, con `./publish_confluence.sh`. Funciona desde cualquier directorio.

Salida esperada:

```
Espacio TODO_CLP_BLP_SPACE · padre <id de la homepage>
63 páginas · espacio TODO_CLP_BLP_SPACE · macro de diagramas 'image'
  creada       ms-clp-blp
  ...
Listo: 63 creadas, 0 actualizadas, 0 sin tocar. No se borró nada.
```

Variables de entorno para casos puntuales: `CONFLUENCE_SPACE`, `CONFLUENCE_PARENT_ID`,
`MERMAID_MACRO`. Cualquier flag extra pasa tal cual al publicador y gana sobre estos valores
(`--allow-overwrite`, `--title-prefix`, `--manifest`, `--strict`).

## Editar directamente en Confluence

Cualquiera puede corregir una página en Confluence. El publicador lo nota —Confluence incrementa la
versión de la página en toda edición, y él guarda en una *content property* (`msClpBlpSync`) la versión
que dejó y la huella de lo que generó— y a partir de ahí **no la vuelve a escribir**.

Estado de cada página en cada corrida, que es lo que imprime `--status`:

| Estado | Significa | Qué hace el publicador |
|---|---|---|
| `nueva` | No existe en Confluence | La crea |
| `sin-cambios` | Idéntica a lo último que publicó | Nada: no gasta una versión |
| `actualizar` | El repo cambió y nadie la editó | La actualiza |
| `conservada` | La editó una persona; el repo no cambió | Nada. La reporta con su URL |
| `conflicto` | La editó una persona **y** el repo cambió | Nada. La reporta como conflicto |

| Flag | Para qué |
|---|---|
| `--status` | Ver la tabla de arriba sin escribir nada. Necesita credenciales (`--dry-run` no) |
| `--force` | Publicar todo, pisando las ediciones de Confluence |
| `--force-page "Título"` | Lo mismo, para una sola página. Se puede repetir |
| `--adopt` | Entregar a Confluence todas las páginas que ya existen: sella su estado actual sin escribir ningún cuerpo |
| `--archive-orphans` | Mover bajo «Archivo (obsoleto)» las páginas que el publicador creó y ya no están en el manifiesto (ver abajo). Con `--status`, solo las lista |

Nada se pierde al pisar una página: Confluence conserva el historial de versiones y desde ahí se
restaura. Un `conflicto` es una decisión humana: lo normal es portar al Markdown lo que se escribió en
Confluence —para que el repo siga siendo la fuente— y republicar.

## Los diagramas

Confluence Cloud no dibuja mermaid de forma nativa y todas las apps del Marketplace que lo hacen se
cobran. Por eso el publicador **renderiza cada bloque ```` ```mermaid ```` a PNG** con
`@mermaid-js/mermaid-cli` (vía `npx`, versión fija en `MERMAID_CLI`), lo sube como adjunto de su
página y lo incrusta donde estaba el bloque. Debajo deja la fuente, plegada, titulada «Fuente del
diagrama (mermaid)».

- El adjunto se llama `diagrama-<hash de la fuente>.png`: el cuerpo de la página no depende de
  renderizar, así que `--status` no necesita Node, y un diagrama que no cambió nunca se vuelve a subir
  con otro nombre.
- Los PNG se cachean en `scripts/.mermaid-cache/` (ignorado por git). Solo se renderizan los de las
  páginas que se van a escribir, y **todos antes de escribir ninguna**: si falta `npx` o un diagrama no
  compila, la corrida aborta sin dejar páginas con la imagen rota.
- Ya no hace falta pegar imágenes a mano, que congelaba la página. Los diagramas pueden vivir en la
  página de la funcionalidad que explican.
- `MERMAID_MACRO=code` vuelve al comportamiento anterior: solo la fuente plegada, sin Node.

## Sintaxis que el publicador entiende además de Markdown

| Escribes | En Confluence sale |
|---|---|
| `summary:` en el front matter | Panel de cabecera (resumen, audiencia, fecha de revisión) + índice automático |
| `> [!NOTE]` / `[!TIP]` / `[!IMPORTANT]` / `[!WARNING]` | Panel info / tip / nota / aviso |
| `## Título {#mi-ancla}` | Un ancla enlazable |
| `[texto](otra-pagina.md#mi-ancla)` | Enlace a esa sección exacta. Cada `# Sección` de una página con `summary` tiene ancla automática (`#soporte`, `#configuracion`) |
| ```` ```json collapse title="Respuesta completa" ```` | Bloque de código plegado y con título |
| Página con hijas en el manifiesto | Macro `children` al final: el índice se mantiene solo |

El dry run valida que cada ancla enlazada exista en la página destino. Detalle en
[`.claude/skills/confluence-docs/SKILL.md`](../.claude/skills/confluence-docs/SKILL.md).

## Archivar las páginas que salieron del manifiesto

El publicador no borra. Cuando una página deja de existir en el manifiesto —por ejemplo al
reorganizar el árbol— sigue viva en Confluence y en la navegación. `--archive-orphans` las aparta:

```bash
scripts/publish_confluence.sh --archive-orphans --status   # lista qué movería, sin tocar nada
scripts/publish_confluence.sh --archive-orphans            # las mueve
```

- Solo toca páginas con el sello `msClpBlpSync`, es decir, creadas por este publicador.
- Las mueve (con su subárbol) bajo la página «Archivo (obsoleto)», que debe estar declarada en el
  manifiesto y publicada antes, y les pone la etiqueta `obsoleta`. Es reversible: basta moverlas de
  vuelta. Los enlaces guardados siguen funcionando.
- Una página que alguien editó en Confluence se reporta y **no** se mueve: primero se porta su
  contenido al Markdown. `--force` la mueve igual.
- Se niega a correr con `--title-prefix` o con otro `--manifest`: un manifiesto parcial haría parecer
  huérfano todo lo demás.

## Front matter

Cada `.md` de `docs/confluence/` empieza con un bloque YAML que declara su identidad, de dónde
se deriva y sus etiquetas. El publicador lo **quita antes de convertir** — Confluence no lo
entiende y lo renderizaría como texto — y con él aplica las etiquetas de la página.

El contrato completo (campos, valores válidos, recetas) está en
[`.claude/skills/confluence-docs/SKILL.md`](../.claude/skills/confluence-docs/SKILL.md).

```yaml
---
id: runtime.cache                  # slug estable, no cambia nunca
title: Cache                       # debe coincidir con el manifiesto
type: reference                    # overview | reference | how-to | api | explanation | adr | feature
status: current                    # current | draft | deprecated
owner: equipo-search-browse
lastReviewed: 2026-08-06
sources:                           # el código del que se deriva: el disparador de actualización
  - src/main/resources/application.yaml
tags: [cache, performance, runtime]
---
```

`--dry-run` reporta qué páginas lo tienen incompleto; `--strict` convierte eso en un fallo,
para poder exigirlo desde CI. Las `tags`, más `type-*` y `status-*`, se aplican como etiquetas
de Confluence (solo se añaden, nunca se quitan las existentes).

`sources` es el campo que hace mecánicas las actualizaciones: cuando cambias código, sabes qué
documentar sin adivinar.

```bash
grep -rl "shared/session" docs/confluence --include="*.md"
```

## Qué hace y qué no

Es **idempotente**: identifica cada página por su título dentro del espacio, así que volver a
correrlo actualiza en vez de duplicar.

| Cambio en `docs/confluence/` | Efecto |
|---|---|
| Editas el contenido de un `.md` | La página se actualiza; queda versión nueva en el historial |
| No editas nada | La página **no se toca**: sin cambios no hay escritura ni versión nueva |
| Agregas un `.md` + su entrada en el manifiesto | Se crea la página |
| Mueves una página a otro padre en el manifiesto | Se re-parenta sola |
| Alguien editó la página a mano en Confluence | **Se conserva su edición.** Se reporta al final; solo `--force` la pisa |
| Renombras el `title` en el manifiesto | Crea una página **nueva**; la vieja queda huérfana |
| Borras un `.md` y su entrada del manifiesto | La página **sigue viva** en Confluence |

**El script no borra nada** — solo `POST` y `PUT`, ni una llamada `DELETE`. Por eso no puede
reflejar eliminaciones ni renombres: eso se hace a mano en Confluence. Para mover contenido,
cambia el `file` del manifiesto, nunca el `title`.

`CONFLUENCE_PARENT_ID` no es cosmético: con un padre definido, el publicador resuelve todas las
páginas **antes** de escribir ninguna y aborta si alguna existe fuera de ese padre, para no pisar
contenido ajeno. Sin padre esa verificación queda desactivada.

## Problemas conocidos

| Síntoma | Causa y arreglo |
|---|---|
| `CERTIFICATE_VERIFY_FAILED` | El Python de python.org no trae CAs raíz. El wrapper ya lo resuelve con `certifi`; si tu red hace inspección TLS, exporta `SSL_CERT_FILE` con el bundle corporativo |
| `HTTP 401` | Correo o token mal. Ojo: el correo debe ser el de tu cuenta de Atlassian |
| `HTTP 403` | Tu cuenta no puede crear páginas en el espacio |
| `No existe el espacio '...'` | `CONFLUENCE_SPACE` debe ser la **clave** (`TODO_CLP_BLP_SPACE`), no el nombre (`<nombre del espacio>`) |
| Una página de diagramas sale con "macro desconocida" | Se está usando `MERMAID_MACRO` con el nombre de una app que el sitio no tiene. Los valores `image` (por defecto) y `code` no necesitan ninguna |
| `Hay diagramas que publicar y no está npx` | Instala Node.js, o publica con `MERMAID_MACRO=code` |
| `No se pudo renderizar un diagrama` | La fuente mermaid tiene un error de sintaxis; el mensaje trae el error y el inicio de la fuente. Pruébala en [mermaid.live](https://mermaid.live) |
| Pegué una imagen y una corrida la borró | Solo pasa con `--force`. Sin él la página queda `conservada`; recupera la imagen desde el historial de versiones de Confluence |
| `Abortado: estas páginas ya existen...` | Hay títulos que chocan con contenido ajeno del espacio. Usa `--title-prefix`, o renombra en el manifiesto. No uses `--allow-overwrite` sin revisar qué vas a pisar |

## Verificar que no se borró nada

```bash
set -a && . scripts/.env.confluence && set +a
curl -s -u "$CONFLUENCE_EMAIL:$CONFLUENCE_API_TOKEN" \
  "$CONFLUENCE_BASE_URL/wiki/api/v2/spaces/2820374570/pages?limit=250&status=current" \
| python3 -c 'import sys,json;d=json.load(sys.stdin)["results"];print(len(d));[print(p["id"],p["version"]["number"],p["title"]) for p in d]'
```

Las 4 páginas de plantilla que traía el espacio al crearse deben seguir en versión 1.

---

# Sincronizar variables de entorno con Cloud Run

El pipeline de despliegue (`deploy_DEV_QA.yml` / `deploy_PROD.yml`, action
`google-github-actions/deploy-cloudrun@v2`) nunca trae `--set-env-vars`: la configuración de runtime
del servicio se agrega fuera de banda, a mano, en Cloud Run (ver
[`docs/confluence/development/deployment.md`](../docs/confluence/development/deployment.md)). Este
script automatiza ese paso en los dos sentidos:

- **push** (default) — lee un `.env` local y lo sincroniza contra un servicio de Cloud Run real,
  variable por variable.
- **pull** (`--pull`) — de solo lectura, nunca modifica Cloud Run. Lee el estado real del servicio y
  regenera un archivo tipo `.env.example.<ambiente>` (dev/qa/prod), incluida la referencia a Secret
  Manager de cada variable respaldada por un secreto — **nunca su valor**.

Crear una versión nueva de un secreto y apuntar Cloud Run a ella sigue siendo un paso manual en GCP;
este script no lo hace en ninguno de los dos sentidos, a propósito — encaja con que los desarrolladores
solo tienen permiso para crear versiones de secretos, nunca para leerlos.

## Qué necesitas

| | |
|---|---|
| `gcloud` CLI | Instalado y autenticado (`gcloud auth login`) con permiso para leer y actualizar el servicio |
| `jq` | Ya es dependencia del repo (lo usa `smoke-blocks.sh`) |
| Un `.env` local | El mismo formato que [`.env.example`](../.env.example) |

## Uso

```bash
scripts/sync-cloud-run-env.sh \
  --project crp-dev-dig-searchreplacement \
  --service dig-crp-clp-blp-service-dev \
  --region us-east4 \
  --env-file .env
```

`--project`, `--service` y `--region` también se pueden dar por variable de entorno
(`CLOUD_RUN_PROJECT`, `SERVICE`, `SERVICE_REGION` — los mismos nombres que ya usa el workflow de
despliegue). `--env-file` por defecto es `.env`. Ninguno tiene valor por defecto hardcodeado: sin los
tres primeros (o un atajo `--dev`/`--qa`/`--prod`, ver abajo), el script se detiene con un error
explícito en vez de asumir un proyecto.

### Atajos `--dev` / `--qa` / `--prod`

Fijan `--project`/`--service`/`--region` a los valores conocidos de cada ambiente, para no tener que
escribirlos a mano cada vez:

| Flag | Project | Service | Region |
|---|---|---|---|
| `--dev` | `crp-dev-dig-searchreplacement` | `dig-crp-clp-blp-service-dev` | `us-east4` |
| `--qa` | `crp-qas-dig-searchreplacement` | `dig-crp-clp-blp-service-qa` | `us-east4` |
| `--prod` | `crp-pro-dig-searchreplacement` | `dig-crp-clp-blp-service-prod` | `us-east4` |

```bash
scripts/sync-cloud-run-env.sh --dev --env-file .env
```

Solo se puede dar uno (`--dev --qa` es un error) y **nunca pisan** un `--project`/`--service`/`--region`
dado explícito — el atajo solo rellena lo que falte, así que `--dev --project otro-id` usa
`otro-id` como project y el resto (`service`/`region`) de `--dev`.

```bash
scripts/sync-cloud-run-env.sh ... --dry-run   # ensayo: consulta el servicio, no escribe nada
scripts/sync-cloud-run-env.sh ... --yes       # no interactivo: confirma el default de cada prompt
```

Para `--pull`, `--env-file` pasa a ser la **plantilla** a recorrer, con default `.env.example` en vez
de `.env`, y `--out` (el archivo a generar) es obligatorio salvo que se use un atajo de ambiente, que
también rellena `--out` con `.env.example.<ambiente>`:

```bash
scripts/sync-cloud-run-env.sh --pull --dev    # equivale a --pull --dev --out .env.example.dev
scripts/sync-cloud-run-env.sh --pull --qa     # equivale a --pull --qa  --out .env.example.qa
scripts/sync-cloud-run-env.sh --pull --prod   # equivale a --pull --prod --out .env.example.prod
```

El script rechaza correr si `--out` resuelve al mismo archivo que `--env-file`, para que nunca se
pise el `.env.example` mantenido a mano.

## Qué hace y qué no

Usa `gcloud run services update --update-env-vars` (nunca `--set-env-vars`, que reemplaza todo el
set de variables del servicio), así que **lo que ya está en Cloud Run y no está en el `.env` se
queda intacto**. Primero recorre el `.env` y decide, variable por variable, qué se crea/actualiza;
las variables **planas** confirmadas se juntan y se mandan al final **en una sola llamada**, así que
esa parte de la corrida queda en una sola revisión nueva — no una por variable. Esa llamada final es
todo o nada: si falla, ninguna de las variables planas pendientes se aplica (ver "Problemas
conocidos"). No hace falta redesplegar: Cloud Run enruta tráfico nuevo a la revisión actualizada de
inmediato.

Las variables que **tocan Secret Manager** (crear un secreto nuevo, migrar una plana a un secreto, o
rotar la versión de uno existente — ver la sección siguiente) no entran en ese lote: cada una se
aplica de inmediato, apenas se confirma, con su propia llamada a Cloud Run. **Una corrida que mezcla
variables planas y secretos puede terminar en más de una revisión nueva del servicio** — una por cada
secreto tocado, más una para el lote de planas.

Cada variable del `.env` se clasifica contra el estado real del servicio y **toda variable —nueva o
existente— pasa por una confirmación explícita antes de aplicarse**. El script imprime qué hace cada
opción antes de preguntar, no solo el hint:

| Estado | Significa | Opciones que se muestran | Default (y lo que responde `--yes`) |
|---|---|---|---|
| `[NEW]` | No existe en el servicio | `p` la crea plana (default) / `s` la crea como secreto nuevo en Secret Manager / `n` no la crea | `p` |
| `[UNCHANGED]` | Existe con el mismo valor | Ninguna: se salta sin preguntar — **salvo** que el nombre sugiera un secreto, en cuyo caso ofrece `m` (migrar a Secret Manager con ese mismo valor) | no hacer nada |
| `[EXISTS]` | Existe con otro valor | `y` sobrescribe (sigue plana) / `m` migra a Secret Manager con el valor de `.env` / `n` conserva | `n` |
| `[SECRET]` | Existe respaldada por Secret Manager (`valueFrom.secretKeyRef`) | `u` crea una versión nueva del secreto y apunta Cloud Run a ella / escribir `PLAINTEXT` sobrescribe (pasa a texto plano) / cualquier otra cosa conserva | conserva |

`--yes` responde el **default de cada fila**, no siempre "sí": bajo `--yes`, `[NEW]` se sigue creando
como plana pero nada más se mueve — `[EXISTS]`/`[UNCHANGED]`/`[SECRET]` se dejan intactos, igual que
en modo interactivo con Enter. **Ninguna opción que toque Secret Manager (`s`/`m`/`u`/`PLAINTEXT`) se
auto-confirma nunca con `--yes`**, sin importar el default — crear/migrar/rotar un secreto siempre
requiere una sesión interactiva. Un valor vacío en `.env` deshabilita `s`/`m`/`u` para esa variable
(no tiene sentido crear o actualizar un secreto con un valor vacío) y el script lo explica en la
opción.

`[UNCHANGED]` (para una variable que no parece un secreto) es lo que hace que correrlo dos veces con
el mismo `.env` no produzca cambios. El `.env` se parsea a mano (sin `source`, sin `cut`) para
soportar valores con espacios, `=`, comillas, comentarios, líneas `export KEY=value` y CRLF sin
romperse. Una `KEY` repetida en `.env` se detecta y se ignora en su segunda aparición (con aviso):
sin esto, la segunda aparición se evaluaría contra el estado de Cloud Run *antes* de la corrida, no
contra lo que la primera aparición acaba de aplicar.

## Crear, migrar y rotar secretos (`s` / `m` / `u`)

El script puede crear secretos nuevos y agregarles versiones, pero **nunca lee el valor de un
secreto** — jamás llama a `gcloud secrets versions access` ni nada equivalente. El valor de `.env`
solo viaja por `stdin` hacia `gcloud secrets versions add --data-file=-`, nunca como argumento de
línea de comandos ni a un log.

- **Nombre del secreto**: por defecto, el mismo nombre que la variable — así ya existen los secretos
  reales de este proyecto (`WISHLIST_APIKEY`, `SALESFORCE_AUTHORIZATION_PROD`), nunca en minúsculas
  con guiones. El prompt lo muestra como default (Enter lo acepta) y deja escribir otro nombre —
  necesario para el sufijo `_PROD`/`_QA`/`_DEV` que ya usan algunos secretos, o para reusar un secreto
  compartido a propósito entre servicios (p.ej. `SALESFROCE_AUTH_USERNAME`/`_PASSWORD`).
- **Ya existe**: `gcloud secrets create` con un nombre que ya existe no se trata como error — el
  script sigue y le agrega una versión nueva al secreto existente.
- **Falla al crear/agregar versión**: se reporta esa variable como fallida (contador aparte, ver
  "Verificar" en el resumen) y la corrida sigue con la siguiente — no es todo o nada como el lote de
  variables planas.
- **Versión huérfana**: si la versión se creó pero conectarla a Cloud Run falla, el secreto **no**
  queda en un estado inconsistente silencioso — el script lo marca explícitamente en el resumen final
  y muestra el comando exacto (`gcloud run services update ... --update-secrets=...`) para completar
  la conexión a mano.
- **Replicación**: `gcloud secrets create` usa `--replication-policy=automatic` (réplica multi-región
  administrada por Google) cuando crea un secreto nuevo.

## Modo `--pull`: regenerar `.env.example.<ambiente>`

Es la forma de llevar registro de **qué secretos usa cada ambiente** sin mantenerlo a mano: en vez de
un documento que se puede desincronizar del servicio real, `.env.example.dev` / `.env.example.qa` /
`.env.example.prod` se regeneran bajo demanda corriendo `--pull` una vez por ambiente.

Recorre la plantilla (`--env-file`, default `.env.example`) línea por línea preservando comentarios y
estructura tal cual; solo reescribe las líneas `KEY=valor` (y `export KEY=valor`) cuyo nombre existe
como variable válida:

| Estado real en Cloud Run | Qué se escribe en `--out` |
|---|---|
| Variable plana | `KEY=<valor real>` |
| Respaldada por Secret Manager | `KEY=` (**nunca el valor**), con un comentario arriba: `# [Secret Manager] <secreto>:<versión>` |
| No configurada en el servicio | La línea de la plantilla, sin tocar, con `# [Cloud Run] not set` arriba |

**Red de seguridad:** no todo lo que debería ser secreto está migrado a Secret Manager todavía. Si una
variable sigue siendo texto plano en Cloud Run pero su nombre sugiere un secreto (mismo criterio que
`looks_like_secret`), su valor **no se escribe** — se deja en blanco con un aviso, tanto en el archivo
generado como en la terminal — para que `--pull` nunca sea la forma más fácil de filtrar un secreto
sin migrar hacia un archivo pensado para commitearse.

## Seguridad

- **Nunca imprime ni loguea el contenido de los valores** — ni en la salida normal, ni en `--dry-run`,
  ni en un error de `gcloud`. Solo se muestran nombres de variable y su estado.
- Usa la sesión de `gcloud` ya autenticada del desarrollador: no crea ni pide ningún archivo de
  credenciales nuevo, y no toca `.gitignore`.
- Siempre pasa `--project` explícito a cada llamada de `gcloud` — nunca depende de
  `gcloud config set project`.
- Una variable cuyo nombre sugiere un secreto (termina en `_TOKEN`, `_PASSWORD`, `_SECRET`, `_KEY`,
  `_APIKEY`, `_AUTHORIZATION` o `_URI`, el mismo criterio que ya usa `.env.example` para marcar
  `SECRET` inline) imprime un aviso sugiriendo Secret Manager (`--update-secrets`) en vez de un env
  var plano — es solo un aviso, no bloquea ni cambia el destino.
- Una variable respaldada por Secret Manager nunca se sobrescribe en silencio: se marca `[SECRET]`,
  se advierte que sobrescribirla la convierte en texto plano, el default es conservarla, y hace falta
  escribir la palabra `PLAINTEXT` a mano — `--yes` nunca la auto-confirma.
- El script puede **crear** secretos y **agregarles versiones**, pero **nunca los lee**: no hay ni un
  solo llamado a `gcloud secrets versions access` (ni nada equivalente) en todo el script. El valor de
  `.env` viaja únicamente por `stdin` hacia `gcloud secrets versions add --data-file=-`, nunca como
  argumento de línea de comandos ni a un log — es justamente lo que permite que un desarrollador con
  permiso de **crear secretos y versiones, pero no leerlas**, use el script sin problema.
- **Ninguna acción que toque Secret Manager (`s`/`m`/`u`/`PLAINTEXT`) se auto-confirma con `--yes`**,
  sin importar el default de la fila — crear, migrar o rotar un secreto siempre requiere una sesión
  interactiva.
- Un valor vacío en `.env` deshabilita `s`/`m`/`u` para esa variable — no se puede crear ni actualizar
  un secreto con un valor vacío (protege, entre otros casos, contra correr el script con un
  `.env.example.<ambiente>` generado por `--pull`, que deja los secretos en blanco a propósito).
- `--pull` es de solo lectura: nunca llama a `gcloud run services update` ni a nada de Secret Manager.
  Solo lee el nombre y la versión del secreto que respalda cada variable (`valueFrom.secretKeyRef`),
  jamás su valor — ni con `gcloud secrets versions access` ni de ninguna otra forma.
- `--pull` nunca escribe el valor de una variable cuyo nombre sugiere un secreto pero que sigue siendo
  texto plano en Cloud Run (ver "Red de seguridad" arriba) — solo la redacta y avisa.

## Problemas conocidos

| Síntoma | Causa y arreglo |
|---|---|
| `gcloud: command not found` | Instala el SDK de Google Cloud y corre `gcloud auth login` |
| `No hay ninguna cuenta autenticada en gcloud` | `gcloud auth login` |
| `No se pudo leer el servicio '...'` | Revisa que `--project`, `--service` y `--region` sean correctos y que tu cuenta tenga permiso de lectura sobre Cloud Run en ese proyecto |
| La actualización final de variables planas falla | Es todo o nada: como todo el lote confirmado se manda en una sola llamada, ninguna de las variables planas pendientes se aplica. Se listan todas bajo "Fallidas"; vuelve a correr el script para reintentar |
| Corriendo en un entorno sin terminal (CI, `nohup`) | Usa `--yes`; sin él, cualquier prompt sin `/dev/tty` cae automáticamente al valor por defecto de la tabla de arriba. Ninguna acción de Secret Manager se dispara así, de todas formas |
| `--pull` sin `--out` | `--out PATH` es obligatorio en modo `--pull` (salvo que uses un atajo `--dev`/`--qa`/`--prod`) — dice a qué archivo escribir |
| `--pull` dice "--out no puede ser el mismo archivo que la plantilla" | `--out` y `--env-file` resolvieron a la misma ruta; usa un archivo de salida distinto (p.ej. `.env.example.dev`) |
| Falla crear/actualizar un secreto (`s`/`m`/`u`) | Es independiente por variable, no todo-o-nada: se reporta esa variable como fallida y la corrida sigue. Revisá el mensaje de `gcloud` que se imprime debajo del error |
| "Se creó la versión N ... pero no se pudo conectar Cloud Run" | La versión del secreto quedó huérfana (existe, no conectada). El script imprime el comando `gcloud run services update ... --update-secrets=...` exacto para completar la conexión a mano. Una causa común: la identidad que corre el script no tiene permiso para que Cloud Run le otorgue `roles/secretmanager.secretAccessor` a la cuenta de servicio del runtime sobre el secreto nuevo |

## Códigos de salida

| Código | Significado |
|---|---|
| `0` | Éxito — todo lo confirmado se aplicó (o `--dry-run`/`--pull` terminaron sin errores) |
| `1` | La actualización final de variables planas falló (todo o nada), y/o al menos una operación de Secret Manager (`s`/`m`/`u`) falló — cada una independiente, ver el resumen final para el detalle |
| `2` | Error de uso o de entorno: `gcloud`/`jq` ausente, sin autenticación, `.env`/`--out` inválidos, o proyecto/servicio/región inválidos |

## Verificación de este script

No hay framework de pruebas de shell en el repo (ni bats, ni un plugin de Maven que corra shell), así
que no se agregó uno solo para este script. Se verificó con `bash -n` (sintaxis); un `.env` de
prueba que cubre cada caso del parseo (comillas, espacios, `=` en el valor, CRLF, `export`,
comentarios) comparado a mano contra lo esperado; una corrida real de `--dry-run` contra un servicio
de Cloud Run vivo para confirmar que la lectura y clasificación (`NEW` / `UNCHANGED` / `EXISTS`)
funcionan contra datos reales; un `gcloud` simulado (mock en `PATH`) devolviendo un `describe --json`
con variables planas y respaldadas por Secret Manager para probar `--pull` de punta a punta —
incluida la redacción de una variable con nombre de secreto que seguía en texto plano — y el
round-trip del archivo generado como `--env-file` de una corrida push en `--dry-run`; confirmando
que `ask()` bajo `--yes` deja intactos `[EXISTS]` y `[SECRET]` (el bug que motivó el arreglo) en vez
de sobrescribirlos como hacía antes; probando los atajos `--dev`/`--qa`/`--prod` — que derivan
project/service/region/`--out` correctamente, que combinar dos (`--dev --qa`) es un error explícito,
y que un `--project`/`--out` explícito dado junto a un atajo gana sobre el valor del atajo; y, para
`s`/`m`/`u`, extendiendo el `gcloud` simulado con `secrets create`/`secrets versions add`/`run
services update --update-secrets` (con una sesión interactiva real vía `expect`, ya que estos prompts
leen de `/dev/tty`) para confirmar: creación, migración y rotación exitosas; que "ya existe" en
`secrets create` no es un error fatal; que un valor vacío deshabilita `s`/`m`/`u`; el caso de versión
huérfana (creada pero sin conectar) y su mensaje de recuperación; que ninguna de las tres llama jamás
a `gcloud secrets versions access` ni imprime el valor real de una variable; que `--dry-run` no hace
ninguna llamada real a `gcloud secrets`/`--update-secrets`; que `--yes` nunca dispara `s`/`m`/`u`; y
que una `KEY` repetida en `.env` se ignora en su segunda aparición, con aviso.
