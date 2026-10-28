#!/usr/bin/env bash
#
# Sincroniza las variables de entorno de un archivo .env local contra un servicio de Cloud Run, o lee
# el estado real de un servicio para regenerar un archivo de referencia por ambiente (--pull).
#
# Modo push (default) — comportamiento incremental: nunca usa `--set-env-vars` (que reemplaza TODO el
# set de variables del servicio). Primero recorre --env-file y decide, variable por variable, qué se
# crea/actualiza y qué no. Los cambios de variables PLANAS se juntan y se mandan al final en una sola
# llamada a `--update-env-vars`, así que esa parte queda en **una sola revisión nueva** — no una por
# variable. Los cambios que tocan Secret Manager (crear un secreto nuevo, migrar una variable plana a
# un secreto, o rotar la versión de uno existente) se aplican de inmediato, uno por uno, apenas se
# confirman — cada uno es su propia llamada a `gcloud secrets ...` + `gcloud run services update
# --update-secrets`, así que **una corrida que toca secretos puede producir más de una revisión nueva**
# (una por cada secreto tocado, más una para el lote de variables planas). El script puede crear
# secretos y agregarles versiones, pero **nunca lee el valor de un secreto** — jamás llama a `gcloud
# secrets versions access` ni nada equivalente. Deja intacto todo lo que ya estaba en el servicio y no
# está en --env-file.
#
# Modo pull (--pull) — de solo lectura, nunca modifica Cloud Run. Recorre una plantilla (--env-file,
# por defecto .env.example en este modo) preservando comentarios y estructura, y reescribe cada línea
# `KEY=valor` con el estado real del servicio: el valor plano tal cual está en Cloud Run, o —si la
# variable está respaldada por Secret Manager— el nombre y versión del secreto en un comentario y el
# valor en blanco (nunca el valor real del secreto). Pensado para generar/actualizar
# .env.example.dev / .env.example.qa / .env.example.prod y así llevar registro de qué secretos usa
# cada ambiente sin que el script llegue a leerlos jamás.
#
# Usage:
#   scripts/sync-cloud-run-env.sh --project ID --service NAME --region REGION [--env-file PATH]
#   scripts/sync-cloud-run-env.sh --dev|--qa|--prod ...   # atajo: fija project/service/region
#                                                          # conocidos (tabla abajo) — no pisa un
#                                                          # --project/--service/--region dado a mano
#   scripts/sync-cloud-run-env.sh --dry-run ...   # solo muestra qué haría, no toca Cloud Run
#   scripts/sync-cloud-run-env.sh --yes ...       # responde el default de cada prompt (CI/no interactivo)
#   scripts/sync-cloud-run-env.sh --pull --project ID --service NAME --region REGION --out PATH \
#                                  [--env-file PLANTILLA]   # regenera PATH desde el servicio real
#   scripts/sync-cloud-run-env.sh --pull --dev|--qa|--prod  # atajo: regenera .env.example.<ambiente>
#
# --dev/--qa/--prod:
#   --dev  -> crp-dev-dig-searchreplacement / dig-crp-clp-blp-service-dev  / us-east4
#   --qa   -> crp-qas-dig-searchreplacement / dig-crp-clp-blp-service-qa   / us-east4
#   --prod -> crp-pro-dig-searchreplacement / dig-crp-clp-blp-service-prod / us-east4
#
# Variables de entorno como fallback de los flags (mismos nombres que .github/workflows/deploy_DEV_QA.yml):
#   CLOUD_RUN_PROJECT, SERVICE, SERVICE_REGION
#
# Ejemplo (push):
#   scripts/sync-cloud-run-env.sh --dev --env-file .env
#
# Ejemplo (pull, uno por ambiente):
#   scripts/sync-cloud-run-env.sh --pull --dev    # escribe .env.example.dev
#   scripts/sync-cloud-run-env.sh --pull --qa     # escribe .env.example.qa
#   scripts/sync-cloud-run-env.sh --pull --prod   # escribe .env.example.prod
#
# Para cada variable de --env-file en modo push se pregunta explícitamente qué hacer (excepto --yes):
#   - No existe en Cloud Run        -> [NEW]       p = plana (default) / s = como secreto nuevo en
#                                       Secret Manager / n = no crearla
#   - Existe, mismo valor           -> [UNCHANGED]  se salta sin preguntar (idempotencia) — salvo que
#                                       el nombre sugiera un secreto, en cuyo caso ofrece m = migrar a
#                                       Secret Manager con ese mismo valor (default: no hacer nada)
#   - Existe, valor distinto        -> [EXISTS]     y = sobrescribe (sigue plana) / m = migra a Secret
#                                       Manager con el valor de .env / n = conserva (default)
#   - Respaldada por Secret Manager -> [SECRET]     u = crea una versión nueva del secreto y apunta
#                                       Cloud Run a ella / PLAINTEXT (escrito completo) = sobrescribe
#                                       como texto plano / cualquier otra cosa conserva (default)
# s/m/u crean/actualizan un secreto real en GCP (nunca leen uno) — ver "Secretos" más abajo. --yes
# responde el default de cada prompt (nunca "PLAINTEXT", nunca s/m/u), así que un run desatendido
# jamás toca Secret Manager, degrada un secreto a texto plano, ni sobrescribe un [EXISTS] en silencio.
# Un valor vacío en .env deshabilita s/m/u para esa variable (no se puede crear/actualizar un secreto
# con un valor vacío). Las variables que están en Cloud Run pero no en --env-file nunca se tocan.
#
# Secretos (s/m/u): el nombre del secreto por defecto es el mismo nombre de la variable (así ya
# existen los secretos reales de este proyecto — SALESFORCE_AUTHORIZATION_PROD —
# nunca en minúsculas con guiones); se puede escribir otro nombre en el prompt para reusar/crear bajo
# uno distinto. `gcloud secrets create` con nombre ya existente no es un error — se le agrega una
# versión igual. Cada secreto tocado es una llamada aparte a Cloud Run (ver arriba, "más de una
# revisión"). Si la versión se crea pero conectarla a Cloud Run falla, queda huérfana — el script lo
# marca explícitamente y muestra el comando exacto para reintentar la conexión.
#
# Exit codes:
#   0  todo salió bien (o no había nada que aplicar / --pull terminó de leer y escribir)
#   1  la actualización final de variables planas falló (todo o nada, ver arriba), y/o al menos una
#      operación de Secret Manager falló (cada una independiente — ver el resumen para el detalle)
#   2  error de uso o de entorno (gcloud/jq ausentes, sin auth, .env inválido, servicio inexistente)
set -euo pipefail

# ── UX: colores solo si la salida es una terminal (igual que smoke-blocks.sh) ──────────────────────
if [[ -t 1 ]]; then
	RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BLUE=$'\033[34m'
	DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
else
	RED=; GREEN=; YELLOW=; BLUE=; DIM=; BOLD=; OFF=
fi

die() { echo "${RED}Error:${OFF} $*" >&2; exit 2; }
warn() { echo "${YELLOW}Aviso:${OFF} $*" >&2; }
info() { echo "$*"; }

# ruta absoluta best-effort: si el directorio todavía no existe (p.ej. --out apunta a un archivo por
# crear en un directorio inexistente) cae al valor sin resolver — solo se usa para comparar rutas.
abspath() {
	local dir base
	dir="$(cd "$(dirname "$1")" 2>/dev/null && pwd)" || dir="$(dirname "$1")"
	base="$(basename "$1")"
	echo "$dir/$base"
}

# ── atajos de ambiente: --dev/--qa/--prod fijan project/service/region conocidos ───────────────────
# Región es la misma para los tres ambientes en deploy_DEV_QA.yml/deploy_PROD.yml (SERVICE_REGION:
# us-east4). Nunca pisan un --project/--service/--region dado explícito (ver más abajo, tras el
# parseo) — solo rellenan lo que el usuario no haya dado a mano.
ENV_PROJECT_DEV="crp-dev-dig-searchreplacement";  ENV_SERVICE_DEV="dig-crp-clp-blp-service-dev"
ENV_PROJECT_QA="crp-qas-dig-searchreplacement";   ENV_SERVICE_QA="dig-crp-clp-blp-service-qa"
ENV_PROJECT_PROD="crp-pro-dig-searchreplacement"; ENV_SERVICE_PROD="dig-crp-clp-blp-service-prod"
ENV_REGION_DEFAULT="us-east4"

set_env_name() {
	if [[ -n "$ENV_NAME" ]]; then
		echo "flag desconocido: --$1 (ya se dio --$ENV_NAME antes; usa un solo --dev/--qa/--prod)" >&2
		exit 2
	fi
	ENV_NAME="$1"
}

# ── flags ────────────────────────────────────────────────────────────────────────────────────────
PROJECT="${CLOUD_RUN_PROJECT:-}"
SERVICE="${SERVICE:-}"
REGION="${SERVICE_REGION:-}"
PROJECT_EXPLICIT=false
SERVICE_EXPLICIT=false
REGION_EXPLICIT=false
ENV_NAME=""
ENV_FILE=".env"
ENV_FILE_EXPLICIT=false
DRY_RUN=false
ASSUME_YES=false
PULL=false
OUT_FILE=""

usage() {
	sed -n '3,52p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--project) PROJECT="$2"; PROJECT_EXPLICIT=true; shift 2 ;;
	--service) SERVICE="$2"; SERVICE_EXPLICIT=true; shift 2 ;;
	--region) REGION="$2"; REGION_EXPLICIT=true; shift 2 ;;
	--dev) set_env_name dev; shift ;;
	--qa) set_env_name qa; shift ;;
	--prod) set_env_name prod; shift ;;
	--env-file) ENV_FILE="$2"; ENV_FILE_EXPLICIT=true; shift 2 ;;
	--dry-run) DRY_RUN=true; shift ;;
	--yes) ASSUME_YES=true; shift ;;
	--pull) PULL=true; shift ;;
	--out) OUT_FILE="$2"; shift 2 ;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "flag desconocido: $1 (prueba --help)" >&2
		exit 2
		;;
	esac
done

# aplica el atajo de ambiente (si se dio) a lo que no haya venido explícito por flag/env var.
if [[ -n "$ENV_NAME" ]]; then
	case "$ENV_NAME" in
	dev) SHORTCUT_PROJECT="$ENV_PROJECT_DEV"; SHORTCUT_SERVICE="$ENV_SERVICE_DEV" ;;
	qa) SHORTCUT_PROJECT="$ENV_PROJECT_QA"; SHORTCUT_SERVICE="$ENV_SERVICE_QA" ;;
	prod) SHORTCUT_PROJECT="$ENV_PROJECT_PROD"; SHORTCUT_SERVICE="$ENV_SERVICE_PROD" ;;
	esac
	$PROJECT_EXPLICIT || PROJECT="$SHORTCUT_PROJECT"
	$SERVICE_EXPLICIT || SERVICE="$SHORTCUT_SERVICE"
	$REGION_EXPLICIT || REGION="$ENV_REGION_DEFAULT"
fi

# en modo --pull, --env-file es la plantilla a recorrer (no lo que se manda a Cloud Run) y por
# defecto es .env.example en vez de .env, salvo que el usuario haya pasado --env-file explícito.
if $PULL && ! $ENV_FILE_EXPLICIT; then
	ENV_FILE=".env.example"
fi

# en modo --pull, --dev/--qa/--prod también rellenan --out con .env.example.<ambiente> si no se dio
# uno explícito, así que un pull completo es solo `--pull --dev` (o --qa/--prod).
if $PULL && [[ -z "$OUT_FILE" && -n "$ENV_NAME" ]]; then
	OUT_FILE=".env.example.$ENV_NAME"
fi

[[ -n "$PROJECT" ]] || die "falta --project (o --dev/--qa/--prod, o la variable CLOUD_RUN_PROJECT)"
[[ -n "$SERVICE" ]] || die "falta --service (o --dev/--qa/--prod, o la variable SERVICE)"
[[ -n "$REGION" ]] || die "falta --region (o --dev/--qa/--prod, o la variable SERVICE_REGION)"
[[ -f "$ENV_FILE" ]] || die "no existe el archivo --env-file: $ENV_FILE"
[[ -r "$ENV_FILE" ]] || die "sin permiso de lectura sobre --env-file: $ENV_FILE"

if $PULL; then
	[[ -n "$OUT_FILE" ]] ||
		die "--pull requiere --out PATH o --dev/--qa/--prod (para derivar .env.example.<ambiente>)"
	[[ "$(abspath "$ENV_FILE")" != "$(abspath "$OUT_FILE")" ]] ||
		die "--out no puede ser el mismo archivo que la plantilla (--env-file): $OUT_FILE"
fi

# ── prerequisitos ────────────────────────────────────────────────────────────────────────────────
command -v gcloud >/dev/null 2>&1 || die "gcloud no está instalado. https://cloud.google.com/sdk/docs/install"
command -v jq >/dev/null 2>&1 || die "jq no está instalado (brew install jq / apt install jq)"

ACTIVE_ACCOUNT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null || true)"
[[ -n "$ACTIVE_ACCOUNT" ]] || die "no hay ninguna cuenta activa de gcloud. Corre: gcloud auth login"

echo "${BOLD}${BLUE}==================================================${OFF}"
echo "${BOLD}Cloud Run Environment Sync${OFF}"
echo "${BOLD}${BLUE}==================================================${OFF}"
echo "Cuenta   : $ACTIVE_ACCOUNT"
echo "Project  : $PROJECT"
echo "Service  : $SERVICE"
echo "Region   : $REGION"
if $PULL; then
	echo "Modo     : ${BLUE}--pull (solo lectura — plantilla: $ENV_FILE, destino: $OUT_FILE)${OFF}"
else
	echo "Env file : $ENV_FILE"
fi
$DRY_RUN && echo "Modo     : ${YELLOW}--dry-run (no se va a modificar nada)${OFF}"
$ASSUME_YES && echo "Modo     : ${YELLOW}--yes (confirma el default de cada prompt)${OFF}"
echo

# ── parser de .env ───────────────────────────────────────────────────────────────────────────────
# Deliberadamente NO usa `source`/`. file`: bash hace word-splitting en valores sin comillas que
# contienen espacios (FOO=hello world sourceado intenta ejecutar "world" como comando). Este parser
# entiende KEY=value, comillas simples/dobles, valores con "=" dentro, "export KEY=value", líneas
# vacías, comentarios y CRLF (archivos creados en Windows). Compatible con bash 3.2 (macOS) — sin
# arrays asociativos.
ENV_KEYS=()
ENV_VALUES=()

parse_env_file() {
	local file="$1" line_no=0 raw line trimmed key value first last
	while IFS= read -r raw || [[ -n "$raw" ]]; do
		line_no=$((line_no + 1))
		line="${raw%$'\r'}" # CRLF -> LF

		# recorta espacio en blanco inicial
		trimmed="$line"
		while [[ "$trimmed" == [[:space:]]* ]]; do trimmed="${trimmed#?}"; done
		[[ -z "$trimmed" ]] && continue     # línea vacía
		[[ "$trimmed" == "#"* ]] && continue # comentario

		[[ "$trimmed" == "export "* ]] && trimmed="${trimmed#export }"

		if [[ "$trimmed" != *"="* ]]; then
			warn "$file:$line_no: sin '=', se ignora -> $trimmed"
			continue
		fi

		key="${trimmed%%=*}"
		# recorta espacio en blanco al final de la clave (KEY = value)
		while [[ "$key" == *[[:space:]] ]]; do key="${key%?}"; done
		value="${trimmed#*=}"

		if ! [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
			warn "$file:$line_no: nombre de variable inválido, se ignora -> $key"
			continue
		fi

		# quita comillas si envuelven el valor completo — `${v:1:-1}` (longitud negativa) es
		# bash 4.2+; bash 3.2 (el default de macOS) no lo soporta, de ahí el #?/%? equivalente.
		first="${value:0:1}"
		last="${value: -1}"
		if [[ ${#value} -ge 2 && "$first" == '"' && "$last" == '"' ]]; then
			value="${value#?}"
			value="${value%?}"
		elif [[ ${#value} -ge 2 && "$first" == "'" && "$last" == "'" ]]; then
			value="${value#?}"
			value="${value%?}"
		fi

		ENV_KEYS+=("$key")
		ENV_VALUES+=("$value")
	done <"$file"
}

parse_env_file "$ENV_FILE"
[[ ${#ENV_KEYS[@]} -gt 0 ]] || die "$ENV_FILE no tiene ninguna variable válida"

# ── leer el estado actual del servicio ──────────────────────────────────────────────────────────
echo "Consultando Cloud Run..."
DESCRIBE_ERR="$(mktemp)"
trap 'rm -f "$DESCRIBE_ERR"' EXIT

if ! DESCRIBE_JSON="$(gcloud run services describe "$SERVICE" \
	--project="$PROJECT" --region="$REGION" --format=json 2>"$DESCRIBE_ERR")"; then
	echo "${RED}No se pudo leer el servicio de Cloud Run.${OFF}" >&2
	echo "  project=$PROJECT service=$SERVICE region=$REGION" >&2
	echo "  gcloud dijo:" >&2
	sed 's/^/    /' "$DESCRIBE_ERR" >&2
	die "revisa que el proyecto/servicio/región sean correctos y que tengas permiso (roles/run.viewer o superior)"
fi
echo

# Pares name/value separados por Unit Separator (0x1F) dentro de un registro, registros
# separados por Record Separator (0x1E) -- dos bytes de control que jamas aparecen en un
# valor real, asi que sobreviven comas, espacios, "=", "|", URLs y connection strings.
CR_PLAIN_KEYS=()
CR_PLAIN_VALUES=()
while IFS=$'\x1f' read -r -d $'\x1e' name value; do
	CR_PLAIN_KEYS+=("$name")
	CR_PLAIN_VALUES+=("$value")
done < <(echo "$DESCRIBE_JSON" | jq -j '
	(.spec.template.spec.containers[0].env // [])[]
	| select(has("value"))
	| .name + "\u001f" + .value + "\u001e"
')

# Igual que arriba, pero para variables respaldadas por Secret Manager: además del nombre de la
# variable, guarda el nombre del secreto y la versión (`valueFrom.secretKeyRef.{name,key}` — Cloud Run
# llama "key" a lo que en Secret Manager es la versión: un número o "latest"). Nunca se lee el valor
# del secreto, solo esta referencia — es lo único que hace falta para --pull.
CR_SECRET_KEYS=()
CR_SECRET_REF_NAMES=()
CR_SECRET_REF_VERSIONS=()
while IFS=$'\x1f' read -r -d $'\x1e' name ref_name ref_version; do
	CR_SECRET_KEYS+=("$name")
	CR_SECRET_REF_NAMES+=("$ref_name")
	CR_SECRET_REF_VERSIONS+=("$ref_version")
done < <(echo "$DESCRIBE_JSON" | jq -j --arg us "$(printf '\x1f')" --arg rs "$(printf '\x1e')" '
	(.spec.template.spec.containers[0].env // [])[]
	| select(has("valueFrom"))
	| .name + $us + (.valueFrom.secretKeyRef.name // "?") + $us + (.valueFrom.secretKeyRef.key // "latest") + $rs
')

# ── heurística: variables que probablemente deberían ir a Secret Manager ───────────────────────────
# Solo informa (no bloquea, no cambia el destino) — mismo criterio que ya usa .env.example para
# marcar "SECRET" inline (_TOKEN, _PASSWORD, _SECRET, _KEY, _APIKEY, _AUTHORIZATION, _URI).
looks_like_secret() {
	[[ "$1" =~ (_TOKEN|_PASSWORD|_SECRET|_KEY|_APIKEY|_AUTHORIZATION|_URI)$ ]]
}

# ── prompt: y/n con default, respeta --yes ──────────────────────────────────────────────────────
# $1 = pregunta, $2 = default ("y" o "n") -> imprime "y" o "n" en stdout
# --yes responde el DEFAULT de cada prompt, no siempre "y": [NEW] es default "y" así que --yes lo
# sigue creando, pero [EXISTS] y [SECRET] son default "n" así que --yes los deja intactos. Antes esta
# función respondía "y" a todo bajo --yes sin importar el default, lo que hacía que un run
# desatendido pudiera sobrescribir un [EXISTS] divergente o degradar un secreto a texto plano sin que
# nadie lo pidiera — justo lo contrario de lo que documenta scripts/README.md.
ask() {
	local question="$1" default="$2" reply hint
	if $ASSUME_YES; then
		echo "$default"
		return
	fi
	[[ "$default" == "y" ]] && hint="[Y/n]" || hint="[y/N]"
	# printf explícito, no `read -p`: bash manda el prompt de -p a stderr y el 2>/dev/null de abajo
	# (necesario para que el error de "no hay /dev/tty" no ensucie la salida) se lo comería también.
	printf '  %s %s: ' "$question" "$hint" >&2
	read -r reply 2>/dev/null </dev/tty || reply=""
	reply="${reply:-$default}"
	case "$reply" in
	[Yy]*) echo "y" ;;
	*) echo "n" ;;
	esac
}

# ── prompt de opción múltiple, bash 3.2-safe (sin ${var,,}), respeta --yes ─────────────────────────
# $1=pregunta $2="letras válidas separadas por espacio" $3=default -> imprime la letra elegida
# (siempre una de $2; vacío o inválido cae al default). --yes siempre responde el default, igual que
# ask() — ninguna acción que toque Secret Manager es el resultado automático de un run desatendido.
ask_choice() {
	local question="$1" choices="$2" default="$3" reply lower c
	if $ASSUME_YES; then
		echo "$default"
		return
	fi
	printf '  %s ' "$question" >&2
	read -r reply 2>/dev/null </dev/tty || reply=""
	lower="$(printf '%s' "$reply" | tr '[:upper:]' '[:lower:]')"
	for c in $choices; do
		[[ "$c" == "$lower" ]] && { echo "$lower"; return; }
	done
	echo "$default"
}

# ── [SECRET]: una sola pregunta con tres desenlaces posibles ────────────────────────────────────────
# $1 = "true"/"false" -> si se ofrece "u" (rotar: nueva versión del secreto existente). "PLAINTEXT"
# sigue siendo case-sensitive a propósito (igual que la confirm_plaintext_override() que reemplaza) y
# --yes NUNCA dispara "update" ni "plaintext" sin importar el default — ninguna acción que toque
# Secret Manager o degrade un secreto es el resultado automático de un run desatendido.
ask_secret_action() {
	local offer_update="$1" reply lower
	if $ASSUME_YES; then
		echo "none"
		return
	fi
	printf '  Elegí una opción (Enter = no hacer nada): ' >&2
	read -r reply 2>/dev/null </dev/tty || reply=""
	lower="$(printf '%s' "$reply" | tr '[:upper:]' '[:lower:]')"
	if [[ "$offer_update" == "true" && "$lower" == "u" ]]; then
		echo "update"
	elif [[ "$reply" == "PLAINTEXT" ]]; then
		echo "plaintext"
	else
		echo "none"
	fi
}

# ── nombre del secreto: por defecto el mismo nombre que la variable ─────────────────────────────────
# Los secretos reales que ya existen en este proyecto usan exactamente el nombre de la variable
# (SALESFORCE_AUTHORIZATION_PROD, MS_CLP_BLP_SECURITY_USERNAME...), nunca minúsculas con
# guiones, así que no hace falta ninguna transformación — y los nombres de variable ya son un
# subconjunto válido de caracteres para un id de secreto de Secret Manager. Enter acepta el default;
# escribir otro nombre reusa/crea bajo ese nombre (necesario para el sufijo _PROD/_QA/_DEV que ya usan
# algunos secretos, o para un secreto compartido a propósito entre servicios).
resolve_secret_name() {
	local key="$1" reply hint=""
	if $ASSUME_YES; then
		echo "$key"
		return
	fi
	if [[ -n "$ENV_NAME" ]]; then
		hint=" (o \"${key}_$(printf '%s' "$ENV_NAME" | tr '[:lower:]' '[:upper:]')\" si querés distinguirlo por ambiente, como ya se hizo con algunos secretos existentes)"
	fi
	echo "  Nombre del secreto en Secret Manager${hint}." >&2
	printf '  Nombre del secreto [%s]: ' "$key" >&2
	read -r reply 2>/dev/null </dev/tty || reply=""
	echo "${reply:-$key}"
}

# ── crea/reusa un secreto, le agrega una versión con el valor de .env, y apunta Cloud Run a la
# versión nueva. mode="migrate" además saca la variable plana en la misma llamada a Cloud Run.
# Nunca llama a `gcloud secrets versions access` ni nada que lea el valor de un secreto — el valor
# solo viaja por stdin hacia `gcloud secrets versions add`, jamás como argumento ni a un log. Bajo
# --dry-run no hace ninguna llamada a gcloud, solo imprime qué haría.
# $1=key $2=value $3=secret_name $4=mode(new|migrate|rotate) -> 0 si se aplicó (o dry-run), 1 si falló
apply_secret_change() {
	local key="$1" value="$2" secret_name="$3" mode="$4"
	local err_file version_json new_version
	local -a update_args

	if [[ -z "$value" ]]; then
		echo "  ${RED}✗ el valor en .env está vacío — no se crea ni actualiza el secreto.${OFF}" >&2
		return 1
	fi

	if $DRY_RUN; then
		case "$mode" in
		new)
			echo "  → (dry-run) crearía el secreto '$secret_name' (o lo reusaría si ya existe), le agregaría una versión con el valor de .env, y apuntaría $key a esa versión nueva."
			;;
		migrate)
			echo "  → (dry-run) crearía/reusaría el secreto '$secret_name', le agregaría una versión con el valor de .env, y movería $key de variable plana a Secret Manager en la misma actualización."
			;;
		rotate)
			echo "  → (dry-run) crearía una versión nueva del secreto '$secret_name' con el valor de .env y apuntaría $key a esa versión nueva."
			;;
		esac
		return 0
	fi

	if [[ "$mode" != "rotate" ]]; then
		err_file="$(mktemp)"
		if ! gcloud secrets create "$secret_name" --project="$PROJECT" \
			--replication-policy="automatic" --quiet >/dev/null 2>"$err_file"; then
			if ! grep -qi "already exists\|ALREADY_EXISTS" "$err_file"; then
				echo "  ${RED}✗ No se pudo crear el secreto '$secret_name':${OFF}" >&2
				sed 's/^/    /' "$err_file" >&2
				rm -f "$err_file"
				return 1
			fi
		fi
		rm -f "$err_file"
	fi

	err_file="$(mktemp)"
	if ! version_json="$(printf '%s' "$value" | gcloud secrets versions add "$secret_name" \
		--project="$PROJECT" --data-file=- --format=json 2>"$err_file")"; then
		echo "  ${RED}✗ No se pudo agregar una versión nueva a '$secret_name':${OFF}" >&2
		sed 's/^/    /' "$err_file" >&2
		rm -f "$err_file"
		return 1
	fi
	rm -f "$err_file"

	new_version="$(echo "$version_json" | jq -r '.name | split("/") | last')"
	if ! [[ "$new_version" =~ ^[0-9]+$ ]]; then
		echo "  ${RED}✗ No se pudo determinar el número de versión nueva de '$secret_name'.${OFF}" >&2
		return 1
	fi

	update_args=(run services update "$SERVICE" --project="$PROJECT" --region="$REGION")
	[[ "$mode" == "migrate" ]] && update_args+=(--remove-env-vars="$key")
	update_args+=(--update-secrets="$key=$secret_name:$new_version" --quiet)

	err_file="$(mktemp)"
	if ! gcloud "${update_args[@]}" >/dev/null 2>"$err_file"; then
		echo "  ${RED}✗ Se creó la versión $new_version de '$secret_name' pero no se pudo conectar Cloud Run:${OFF}" >&2
		sed 's/^/    /' "$err_file" >&2
		echo "  ${YELLOW}La versión quedó creada pero sin conectar. Para completarlo, corré:${OFF}" >&2
		printf '   ' >&2
		printf ' %q' gcloud "${update_args[@]}" >&2
		echo >&2
		rm -f "$err_file"
		SECRET_ORPHANED+=("$key -> $secret_name:$new_version")
		return 1
	fi
	rm -f "$err_file"
	echo "  ${GREEN}✓ $key conectada a Secret Manager ($secret_name:$new_version).${OFF}"
	return 0
}

# ── delimitador para --update-env-vars que no choque con NINGÚN valor del lote (gcloud topic escaping) ──
# --update-env-vars separa pares por "," de forma nativa; como aquí se manda el lote completo en una
# sola llamada, el delimitador tiene que estar ausente de TODOS los valores pendientes a la vez (no
# alcanza con que esté ausente de uno solo). gcloud soporta un delimitador alterno de cualquier largo:
# --update-env-vars="^;^KEY=VALUE". Se prueban primero caracteres comunes y legibles; si algún valor
# los usa todos, se cae a un separador de baja probabilidad de colisión.
pick_batch_delimiter() {
	local d v safe nonce attempt
	for d in ',' ';' '|' '~' $'\x1e' $'\x1d' $'\x1c'; do
		safe=true
		for v in "${PENDING_VALUES[@]}"; do
			[[ "$v" == *"$d"* ]] && { safe=false; break; }
		done
		$safe && { echo "$d"; return 0; }
	done
	for attempt in 1 2 3 4 5; do
		nonce="@@${RANDOM}${RANDOM}@@"
		safe=true
		for v in "${PENDING_VALUES[@]}"; do
			[[ "$v" == *"$nonce"* ]] && { safe=false; break; }
		done
		$safe && { echo "$nonce"; return 0; }
	done
	return 1
}

# ── aplica TODO el lote pendiente (PENDING_KEYS/PENDING_VALUES) en una sola llamada a gcloud ───────
# Una sola revisión nueva para todos los cambios confirmados, en vez de una por variable.
apply_pending_batch() {
	local delim pairs=() joined i
	delim="$(pick_batch_delimiter)" || {
		echo "  ${RED}No se encontró un delimitador seguro para este lote de valores.${OFF}" >&2
		return 1
	}
	for i in "${!PENDING_KEYS[@]}"; do
		pairs+=("${PENDING_KEYS[$i]}=${PENDING_VALUES[$i]}")
	done
	joined="${pairs[0]}"
	for ((i = 1; i < ${#pairs[@]}; i++)); do
		joined="${joined}${delim}${pairs[$i]}"
	done
	if [[ "$delim" == "," ]]; then
		gcloud run services update "$SERVICE" \
			--project="$PROJECT" --region="$REGION" \
			--update-env-vars="$joined" \
			--quiet >/dev/null
	else
		gcloud run services update "$SERVICE" \
			--project="$PROJECT" --region="$REGION" \
			--update-env-vars="^${delim}^${joined}" \
			--quiet >/dev/null
	fi
}

# ── modo --pull: helpers de escritura segura de valores en un .env generado ────────────────────────
# Un valor con espacio en blanco al inicio/final, o con "#", queda menos ambiguo entre comillas al
# volver a leerlo. Vacío se deja tal cual ("KEY=", sin comillas) — misma convención que ya usa
# .env.example para un secreto sin valor (p.ej. CONTENT_STACK_LP_API_KEY=).
quote_needed() {
	local v="$1"
	[[ -z "$v" ]] && return 1
	[[ "$v" == *"#"* ]] && return 0
	[[ "$v" != "${v#[[:space:]]}" ]] && return 0
	[[ "$v" != "${v%[[:space:]]}" ]] && return 0
	return 1
}

write_value_line() {
	local prefix="$1" key="$2" value="$3"
	if quote_needed "$value"; then
		value="${value//\"/\\\"}"
		printf '%s%s="%s"\n' "$prefix" "$key" "$value"
	else
		printf '%s%s=%s\n' "$prefix" "$key" "$value"
	fi
}

# ── modo --pull: regenera --out a partir de la plantilla --env-file y el estado real del servicio ──
# Solo lectura: nunca llama a `gcloud run services update` ni a nada de Secret Manager. Recorre la
# plantilla línea por línea preservando comentarios/estructura tal cual; solo reescribe las líneas
# "KEY=..." (y "export KEY=...") cuyo nombre reconoce como variable válida.
run_pull() {
	local out_tmp line_no=0 raw line trimmed prefix rest key
	local secret_idx cr_idx i value
	local live_count=0 secret_count=0 missing_count=0 redacted_count=0

	out_tmp="$(mktemp)"

	while IFS= read -r raw || [[ -n "$raw" ]]; do
		line_no=$((line_no + 1))
		line="${raw%$'\r'}"

		trimmed="$line"
		while [[ "$trimmed" == [[:space:]]* ]]; do trimmed="${trimmed#?}"; done

		if [[ -z "$trimmed" || "$trimmed" == "#"* ]]; then
			echo "$line" >>"$out_tmp"
			continue
		fi

		prefix=""
		rest="$trimmed"
		if [[ "$rest" == "export "* ]]; then
			prefix="export "
			rest="${rest#export }"
		fi

		if [[ "$rest" != *"="* ]]; then
			echo "$line" >>"$out_tmp"
			continue
		fi

		key="${rest%%=*}"
		while [[ "$key" == *[[:space:]] ]]; do key="${key%?}"; done

		if ! [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
			echo "$line" >>"$out_tmp"
			continue
		fi

		# ¿respaldada por Secret Manager en el servicio real? -> nombre del secreto y versión en un
		# comentario, valor en blanco. Nunca se lee ni se escribe el valor del secreto.
		secret_idx=-1
		for i in "${!CR_SECRET_KEYS[@]}"; do
			[[ "${CR_SECRET_KEYS[$i]}" == "$key" ]] && { secret_idx=$i; break; }
		done
		if [[ $secret_idx -ge 0 ]]; then
			echo "# [Secret Manager] ${CR_SECRET_REF_NAMES[$secret_idx]}:${CR_SECRET_REF_VERSIONS[$secret_idx]}" >>"$out_tmp"
			write_value_line "$prefix" "$key" "" >>"$out_tmp"
			secret_count=$((secret_count + 1))
			continue
		fi

		# ¿existe como variable plana en el servicio real?
		cr_idx=-1
		for i in "${!CR_PLAIN_KEYS[@]}"; do
			[[ "${CR_PLAIN_KEYS[$i]}" == "$key" ]] && { cr_idx=$i; break; }
		done
		if [[ $cr_idx -lt 0 ]]; then
			echo "# [Cloud Run] not set" >>"$out_tmp"
			echo "$line" >>"$out_tmp"
			missing_count=$((missing_count + 1))
			continue
		fi

		value="${CR_PLAIN_VALUES[$cr_idx]}"

		# red de seguridad: no todo lo que debería ser secreto está migrado todavía. Si el nombre
		# sugiere un secreto pero sigue siendo texto plano en Cloud Run, no se escribe el valor real
		# — de lo contrario --pull sería la forma más fácil de filtrar un secreto sin migrar a un
		# archivo pensado para commitearse.
		if looks_like_secret "$key"; then
			echo "# [Cloud Run] texto plano pero el nombre sugiere un secreto — valor NO escrito, migrar a Secret Manager" >>"$out_tmp"
			write_value_line "$prefix" "$key" "" >>"$out_tmp"
			warn "$key: valor plano en Cloud Run pero el nombre sugiere que es un secreto — no se escribió su valor en $OUT_FILE."
			redacted_count=$((redacted_count + 1))
			continue
		fi

		write_value_line "$prefix" "$key" "$value" >>"$out_tmp"
		live_count=$((live_count + 1))
	done <"$ENV_FILE"

	if $DRY_RUN; then
		echo "${YELLOW}--dry-run: contenido que se escribiría en $OUT_FILE (no se escribió nada)${OFF}"
		echo
		cat "$out_tmp"
		rm -f "$out_tmp"
	else
		mv "$out_tmp" "$OUT_FILE" || die "no se pudo escribir $OUT_FILE"
		echo "${GREEN}✓ Escrito $OUT_FILE${OFF}"
	fi

	echo
	echo "${BOLD}${BLUE}==================================================${OFF}"
	echo "${BOLD}Resumen (--pull)${OFF}"
	echo "${BOLD}${BLUE}==================================================${OFF}"
	echo "Valores planos escritos      : $live_count"
	echo "Referencias a Secret Manager : $secret_count"
	echo "No configuradas en Cloud Run : $missing_count"
	echo "Redactadas (parecen secreto) : $redacted_count"
	if [[ $redacted_count -gt 0 ]]; then
		echo
		warn "hay variables con nombre de secreto que siguen en texto plano en Cloud Run — sus valores no se escribieron. Migrarlas a Secret Manager cuando se pueda."
	fi
	echo "${BOLD}${BLUE}==================================================${OFF}"
}

if $PULL; then
	run_pull
	exit 0
fi

# ── recorre .env y clasifica cada variable (sin tocar Cloud Run todavía) ───────────────────────────
# Esta pasada solo pregunta y decide. Nada se manda a Cloud Run aquí — lo confirmado se junta en
# PENDING_KEYS/PENDING_VALUES y se aplica en una sola llamada después del bucle (ver más abajo), así
# que todos los cambios de esta corrida quedan en una sola revisión nueva del servicio.
SKIPPED=0
CREATED=0
UPDATED=0
FAILED=0
FAILED_NAMES=()

# Contadores de las acciones que tocan Secret Manager — independientes de FAILED/FAILED_NAMES porque
# cada una es una acción propia, aplicada de inmediato (ver apply_secret_change), no parte del lote
# todo-o-nada de PENDING_*.
SECRETS_CREATED=0
SECRETS_MIGRATED=0
SECRETS_ROTATED=0
SECRET_FAILED=0
SECRET_FAILED_NAMES=()
SECRET_ORPHANED=() # "KEY -> secreto:versión" cuando la versión se creó pero no se pudo conectar a Cloud Run

PENDING_KEYS=()
PENDING_VALUES=()
PENDING_IS_NEW=() # "1" = no existía (cuenta como creada) / "0" = ya existía (cuenta como actualizada)

SEEN_KEYS=()
for i in "${!ENV_KEYS[@]}"; do
	key="${ENV_KEYS[$i]}"
	value="${ENV_VALUES[$i]}"
	is_new=false

	# guarda contra una KEY repetida en --env-file: CR_PLAIN_KEYS/CR_SECRET_KEYS son un snapshot
	# tomado antes del loop y no se actualizan a medida que se aplican cambios, así que una segunda
	# aparición se clasificaría contra datos viejos — p.ej. podría recrear como variable plana una
	# KEY que la primera aparición acaba de migrar a Secret Manager en esta misma corrida.
	is_dup=false
	for s in "${!SEEN_KEYS[@]}"; do
		[[ "${SEEN_KEYS[$s]}" == "$key" ]] && { is_dup=true; break; }
	done
	if $is_dup; then
		warn "$key: aparece más de una vez en $ENV_FILE — se usa solo la primera aparición."
		continue
	fi
	SEEN_KEYS+=("$key")

	# ¿respaldada por Secret Manager? (idioma seguro para bash 3.2 bajo `set -u`: iterar por índice
	# nunca falla con un array vacío, a diferencia de "${CR_SECRET_KEYS[@]}" a secas)
	is_secret=false
	secret_idx=-1
	for s in "${!CR_SECRET_KEYS[@]}"; do
		[[ "${CR_SECRET_KEYS[$s]}" == "$key" ]] && { is_secret=true; secret_idx=$s; break; }
	done

	# ¿existe como variable plana, y con qué valor?
	cr_index=-1
	for j in "${!CR_PLAIN_KEYS[@]}"; do
		[[ "${CR_PLAIN_KEYS[$j]}" == "$key" ]] && { cr_index=$j; break; }
	done

	if $is_secret; then
		echo "${BLUE}[SECRET]${OFF}   $key"
		echo "  Está respaldada por Secret Manager en Cloud Run (secreto: ${CR_SECRET_REF_NAMES[$secret_idx]}, versión: ${CR_SECRET_REF_VERSIONS[$secret_idx]})."
		looks_like_secret "$key" && warn "  (además el nombre sugiere que es sensible)"
		if [[ -n "$value" ]]; then
			echo "    u = crear una versión nueva de este secreto con el valor de .env y apuntar Cloud Run a ella"
		else
			echo "  ${DIM}(el valor en .env está vacío — no se puede crear una versión vacía; para actualizar,"
			echo "  poné el valor real en .env primero)${OFF}"
		fi
		echo "    PLAINTEXT = sobrescribir con el valor de .env de todas formas — pasa a ser texto"
		echo "                plano y deja de leer el secreto"
		echo "    (cualquier otra respuesta) = conservar tal cual ${DIM}(default)${OFF}"
		secret_action="$(ask_secret_action "$([[ -n "$value" ]] && echo true || echo false)")"
		case "$secret_action" in
		update)
			if apply_secret_change "$key" "$value" "${CR_SECRET_REF_NAMES[$secret_idx]}" "rotate"; then
				SECRETS_ROTATED=$((SECRETS_ROTATED + 1))
			else
				SECRET_FAILED=$((SECRET_FAILED + 1))
				SECRET_FAILED_NAMES+=("$key")
			fi
			echo
			continue
			;;
		plaintext)
			: # sigue abajo — mismo camino que antes: se agrega a PENDING_* como texto plano
			;;
		*)
			echo "  → se conserva tal cual."
			SKIPPED=$((SKIPPED + 1))
			echo
			continue
			;;
		esac
	elif [[ $cr_index -eq -1 ]]; then
		echo "${GREEN}[NEW]${OFF}      $key"
		looks_like_secret "$key" && warn "  el nombre sugiere que es un secreto"
		if [[ -n "$value" ]]; then
			echo "    p = crearla en Cloud Run como variable de entorno plana ${DIM}(default)${OFF}"
			echo "    s = crearla en Secret Manager y conectarla a Cloud Run como secreto"
			echo "    n = no crearla — se queda fuera de Cloud Run"
			new_choice="$(ask_choice "No existe en Cloud Run. ¿Cómo la creamos?" "p s n" "p")"
		else
			echo "    p = crearla en Cloud Run como variable de entorno plana ${DIM}(default)${OFF}"
			echo "    n = no crearla — se queda fuera de Cloud Run"
			echo "  ${DIM}(el valor en .env está vacío, así que no se puede crear como secreto)${OFF}"
			new_choice="$(ask_choice "No existe en Cloud Run. ¿Cómo la creamos?" "p n" "p")"
		fi
		case "$new_choice" in
		s)
			secret_name="$(resolve_secret_name "$key")"
			if apply_secret_change "$key" "$value" "$secret_name" "new"; then
				SECRETS_CREATED=$((SECRETS_CREATED + 1))
			else
				SECRET_FAILED=$((SECRET_FAILED + 1))
				SECRET_FAILED_NAMES+=("$key")
			fi
			echo
			continue
			;;
		n)
			echo "  → omitida."
			SKIPPED=$((SKIPPED + 1))
			echo
			continue
			;;
		esac
		is_new=true
	elif [[ "${CR_PLAIN_VALUES[$cr_index]}" == "$value" ]]; then
		if looks_like_secret "$key" && [[ -n "$value" ]]; then
			echo "${DIM}[UNCHANGED]${OFF} $key"
			echo "  Ya existe en Cloud Run con el mismo valor, pero el nombre sugiere que es un secreto."
			echo "    m = migrar a Secret Manager: crea/reusa un secreto con este valor y conecta $key a él"
			echo "    (cualquier otra respuesta) = no hacer nada — se queda como variable plana ${DIM}(default)${OFF}"
			unchanged_choice="$(ask_choice "¿Migrar a Secret Manager?" "m n" "n")"
			if [[ "$unchanged_choice" == "m" ]]; then
				secret_name="$(resolve_secret_name "$key")"
				if apply_secret_change "$key" "$value" "$secret_name" "migrate"; then
					SECRETS_MIGRATED=$((SECRETS_MIGRATED + 1))
				else
					SECRET_FAILED=$((SECRET_FAILED + 1))
					SECRET_FAILED_NAMES+=("$key")
				fi
			else
				echo "  → se queda como variable plana."
				SKIPPED=$((SKIPPED + 1))
			fi
			echo
			continue
		fi
		echo "${DIM}[UNCHANGED] $key${OFF}"
		SKIPPED=$((SKIPPED + 1))
		continue
	else
		echo "${YELLOW}[EXISTS]${OFF}   $key"
		echo "  Ya existe en Cloud Run con un valor distinto."
		if [[ -n "$value" ]]; then
			echo "    y = sobrescribir con el valor de .env (sigue como variable plana)"
			echo "    m = migrar a Secret Manager: crea/reusa un secreto con el valor de .env y conecta $key a él"
			echo "    n = conservar el valor actual en Cloud Run ${DIM}(default)${OFF}"
			exists_choice="$(ask_choice "¿Sobrescribir o migrar?" "y m n" "n")"
		else
			echo "    y = sobrescribir con el valor de .env (vacío)"
			echo "    n = conservar el valor actual en Cloud Run ${DIM}(default)${OFF}"
			exists_choice="$(ask_choice "¿Sobrescribir?" "y n" "n")"
		fi
		case "$exists_choice" in
		m)
			secret_name="$(resolve_secret_name "$key")"
			if apply_secret_change "$key" "$value" "$secret_name" "migrate"; then
				SECRETS_MIGRATED=$((SECRETS_MIGRATED + 1))
			else
				SECRET_FAILED=$((SECRET_FAILED + 1))
				SECRET_FAILED_NAMES+=("$key")
			fi
			echo
			continue
			;;
		n)
			echo "  → se conserva el valor actual."
			SKIPPED=$((SKIPPED + 1))
			echo
			continue
			;;
		esac
	fi

	if $DRY_RUN; then
		echo "  → (dry-run) se agregaría al lote de cambios."
	else
		echo "  → confirmada; se aplicará junto con el resto al final."
	fi
	PENDING_KEYS+=("$key")
	PENDING_VALUES+=("$value")
	$is_new && PENDING_IS_NEW+=("1") || PENDING_IS_NEW+=("0")
	echo
done

# ── aplica todo lo confirmado en una sola actualización de Cloud Run ───────────────────────────────
PENDING_COUNT=${#PENDING_KEYS[@]}

if [[ $PENDING_COUNT -eq 0 ]]; then
	echo "No hay cambios que aplicar."
elif $DRY_RUN; then
	echo "${YELLOW}--dry-run: $PENDING_COUNT variable(s) se aplicarían en una sola actualización de Cloud Run (una sola revisión nueva). No se modificó nada.${OFF}"
	for i in "${!PENDING_KEYS[@]}"; do
		[[ "${PENDING_IS_NEW[$i]}" == "1" ]] && CREATED=$((CREATED + 1)) || UPDATED=$((UPDATED + 1))
	done
else
	echo "Aplicando $PENDING_COUNT variable(s) en una sola actualización de Cloud Run..."
	if apply_pending_batch; then
		for i in "${!PENDING_KEYS[@]}"; do
			[[ "${PENDING_IS_NEW[$i]}" == "1" ]] && CREATED=$((CREATED + 1)) || UPDATED=$((UPDATED + 1))
		done
		echo "${GREEN}✓ Listo — todos los cambios quedaron en una sola revisión nueva del servicio.${OFF}"
	else
		FAILED=$PENDING_COUNT
		FAILED_NAMES=("${PENDING_KEYS[@]}")
		echo "${RED}✗ La actualización falló — es todo o nada: ninguna de las $PENDING_COUNT variables pendientes se aplicó.${OFF}"
	fi
fi
echo

# ── resumen ──────────────────────────────────────────────────────────────────────────────────────
echo "${BOLD}${BLUE}==================================================${OFF}"
echo "${BOLD}Resumen${OFF}"
echo "${BOLD}${BLUE}==================================================${OFF}"
echo "Creadas     : $CREATED"
echo "Actualizadas: $UPDATED"
echo "Omitidas    : $SKIPPED"
echo "Fallidas    : $FAILED"
if [[ $FAILED -gt 0 ]]; then
	echo
	echo "${RED}Variables que no se aplicaron (la actualización falló completa):${OFF}"
	printf '  %s\n' "${FAILED_NAMES[@]}"
fi
if [[ $SECRETS_CREATED -gt 0 || $SECRETS_MIGRATED -gt 0 || $SECRETS_ROTATED -gt 0 || $SECRET_FAILED -gt 0 ]]; then
	echo
	echo "Secretos creados      : $SECRETS_CREATED"
	echo "Secretos migrados     : $SECRETS_MIGRATED"
	echo "Secretos rotados      : $SECRETS_ROTATED"
	echo "Operaciones fallidas  : $SECRET_FAILED"
	if [[ $SECRET_FAILED -gt 0 ]]; then
		echo
		echo "${RED}Variables con una operación de Secret Manager fallida (cada una independiente, no todo-o-nada):${OFF}"
		printf '  %s\n' "${SECRET_FAILED_NAMES[@]}"
	fi
fi
if [[ ${#SECRET_ORPHANED[@]} -gt 0 ]]; then
	echo
	echo "${RED}¡Atención! Versiones de secreto creadas que NO quedaron conectadas a Cloud Run:${OFF}"
	printf '  %s\n' "${SECRET_ORPHANED[@]}"
fi
$DRY_RUN && echo && echo "${YELLOW}--dry-run: no se modificó Cloud Run.${OFF}"
echo "${BOLD}${BLUE}==================================================${OFF}"

[[ $FAILED -gt 0 || $SECRET_FAILED -gt 0 ]] && exit 1
exit 0
