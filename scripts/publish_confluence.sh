#!/usr/bin/env bash
#
# Publica docs/confluence/ en el espacio de Confluence Cloud de ms-clp-blp (clave en CONFLUENCE_SPACE).
#
# Envuelve scripts/publish_confluence.py para que publicar sea un solo comando: prepara el
# virtualenv con las dos dependencias que el publicador necesita (pyyaml, markdown) y aplica
# el espacio y el padre de destino, que viven en scripts/.env.confluence.
#
# Configuración (una sola vez):
#   cp scripts/.env.confluence.example scripts/.env.confluence
#   # rellena CONFLUENCE_EMAIL y CONFLUENCE_API_TOKEN
#
# Uso:
#   scripts/publish_confluence.sh --dry-run          # ensayo: no toca la red ni escribe nada
#   scripts/publish_confluence.sh --status           # qué haría con cada página, sin escribir
#   scripts/publish_confluence.sh                    # publica
#
# Las páginas editadas en Confluence se conservan: el publicador las detecta y las salta.
# --force (o --force-page "Título") es lo único que las pisa.
#
# Cualquier flag extra se pasa tal cual al publicador y gana sobre los valores por defecto.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
VENV="${ROOT}/.venv-confluence"

# scripts/.env.confluence es el nombre canónico; scripts/.env se acepta porque es el que
# genera copiar la plantilla sin renombrar. Ambos los ignora git y los rechaza el hook de
# pre-commit (patrón `.env*`), así que ninguno de los dos puede acabar en un commit.
ENV_FILE="${CONFLUENCE_ENV_FILE:-}"
if [[ -z "$ENV_FILE" ]]; then
	for candidate in "${HERE}/.env.confluence" "${HERE}/.env"; do
		[[ -f "$candidate" ]] && ENV_FILE="$candidate" && break
	done
fi

if [[ -n "$ENV_FILE" && -f "$ENV_FILE" ]]; then
	set -a
	# shellcheck disable=SC1090
	source "$ENV_FILE"
	set +a
fi

# TODO(ms-clp-blp): el espacio de Confluence de este servicio aún no existe. Cuando se cree, pon
# aquí su CLAVE y el id de su homepage (o defínelos en scripts/.env.confluence).
SPACE="${CONFLUENCE_SPACE:-TODO_CLP_BLP_SPACE}"
PARENT_ID="${CONFLUENCE_PARENT_ID:-}"
# 'image' renderiza cada diagrama mermaid a PNG con mermaid-cli (npx, necesita Node) y lo
# adjunta a su página, con la fuente plegada debajo. 'code' publica solo la fuente plegada
# (Confluence Cloud no dibuja mermaid y las apps que lo hacen son de paga).
MERMAID_MACRO="${MERMAID_MACRO:-image}"

# El ensayo no llama a la API, así que no exige credenciales: sirve para validar el
# manifiesto antes de tener el token.
dry_run=false
for arg in "$@"; do
	[[ "$arg" == "--dry-run" ]] && dry_run=true
done

if [[ "$dry_run" == false ]]; then
	missing=""
	for var in CONFLUENCE_BASE_URL CONFLUENCE_EMAIL CONFLUENCE_API_TOKEN; do
		[[ -n "${!var:-}" ]] || missing="${missing} ${var}"
	done
	if [[ -n "$missing" ]]; then
		echo "publish_confluence: faltan variables (o están vacías):${missing}" >&2
		echo "  archivo leído: ${ENV_FILE:-<ninguno>}" >&2
		echo "  rellena ahí CONFLUENCE_EMAIL y CONFLUENCE_API_TOKEN" >&2
		echo "  (token en id.atlassian.com/manage-profile/security/api-tokens)" >&2
		exit 1
	fi
fi

if [[ ! -x "${VENV}/bin/python3" ]]; then
	echo "Creando entorno virtual en ${VENV}"
	python3 -m venv "$VENV"
fi
if ! "${VENV}/bin/python3" -c 'import yaml, markdown, certifi' 2>/dev/null; then
	echo "Instalando dependencias de ${HERE}/requirements.txt"
	"${VENV}/bin/pip" install --quiet --disable-pip-version-check -r "${HERE}/requirements.txt"
fi

# El Python de python.org no instala CAs raíz (a diferencia de curl, que usa el llavero de
# macOS), así que urllib falla el handshake TLS con Atlassian: CERTIFICATE_VERIFY_FAILED.
# Se apunta al bundle de certifi, que ssl lee de SSL_CERT_FILE. Si ya viene definida se
# respeta: en una red con inspección TLS hay que usar el bundle corporativo, no certifi.
if [[ -z "${SSL_CERT_FILE:-}" ]]; then
	SSL_CERT_FILE="$("${VENV}/bin/python3" -c 'import certifi; print(certifi.where())')"
	export SSL_CERT_FILE
fi

echo "Espacio ${SPACE} · padre ${PARENT_ID:-<raíz del espacio>}"

exec "${VENV}/bin/python3" "${HERE}/publish_confluence.py" \
	--space "$SPACE" \
	--parent-id "$PARENT_ID" \
	--mermaid-macro "$MERMAID_MACRO" \
	"$@"
