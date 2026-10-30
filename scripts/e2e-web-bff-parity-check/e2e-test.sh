#!/usr/bin/env bash
# e2e-test.sh — data parity between web-bff and ms-clp-blp for the BLP/CLP pages in a CSV.
#
#   scripts/e2e-web-bff-parity-check/e2e-test.sh --dev|--qa|--prod [options]
#
# Starts ms-clp-blp locally with the repo-root .env.<env> and the matching Spring profile (unless
# --no-start), then for every CSV row compares web-bff's page against ms-clp-blp's first GET plus the
# lazy POSTs the frontend would make. Only data is compared; documented in scripts/README.md.
#
# Exit codes: 0 every page has identical data · 1 differences found · 2 usage/environment error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ENV_NAME=""
CSV="$SCRIPT_DIR/categories-to-test.csv"
MS_URL="http://localhost:8080"
BFF_URL=""
BRAND="LP"
WORKERS=4
START=true
OUT=""
STARTUP_TIMEOUT=180

usage() {
	cat <<EOF
Usage: $(basename "$0") --dev|--qa|--prod [options]

  --dev | --qa | --prod   Environment: reads $REPO_ROOT/.env.<env> and starts the service with that
                          Spring profile. web-bff defaults to that environment's gateway.
  --csv <file>            Pages to test (default: categories-to-test.csv next to this script)
  --bff-url <url>         web-bff base URL, overriding the environment's default
  --ms-url <url>          ms-clp-blp base URL (default: $MS_URL)
  --brand <code>          x-brand-id sent to both services (default: $BRAND)
  --workers <n>           Pages compared in parallel (default: $WORKERS)
  --no-start              Do not start ms-clp-blp; use the one already listening on --ms-url
  --out <dir>             Output directory (default: scripts/e2e-web-bff-parity-check/out/<env>-<timestamp>)
  -h | --help             This help
EOF
}

die() { echo "error: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
	case "$1" in
		--dev | --qa | --prod)
			[[ -z "$ENV_NAME" ]] || die "choose only one of --dev, --qa, --prod"
			ENV_NAME="${1#--}"; shift ;;
		--csv) CSV="${2:?--csv needs a file}"; shift 2 ;;
		--bff-url) BFF_URL="${2:?--bff-url needs a URL}"; shift 2 ;;
		--ms-url) MS_URL="${2:?--ms-url needs a URL}"; shift 2 ;;
		--brand) BRAND="${2:?--brand needs a code}"; shift 2 ;;
		--workers) WORKERS="${2:?--workers needs a number}"; shift 2 ;;
		--no-start) START=false; shift ;;
		--out) OUT="${2:?--out needs a directory}"; shift 2 ;;
		-h | --help) usage; exit 0 ;;
		*) usage >&2; die "unknown argument: $1" ;;
	esac
done

[[ -n "$ENV_NAME" ]] || { usage >&2; die "one of --dev, --qa, --prod is required"; }

# web-bff's public gateway per environment (digital_bff k8s/web-bff/*.values.yaml). Note that the
# production host is ogcp-cms-site-d — the "-d" is not "dev".
case "$ENV_NAME" in
	dev) DEFAULT_BFF="https://ogcp-cms-sit.liverpool.com.mx/web-bff" ;;
	qa) DEFAULT_BFF="https://ogcp-cms-qas.liverpool.com.mx/web-bff" ;;
	prod) DEFAULT_BFF="https://ogcp-cms-site-d.liverpool.com.mx/web-bff" ;;
esac
BFF_URL="${BFF_URL:-$DEFAULT_BFF}"

ENV_FILE="$REPO_ROOT/.env.$ENV_NAME"
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE not found (copy .env.example and fill it in)"
[[ -f "$CSV" ]] || die "CSV not found: $CSV"
command -v python3 >/dev/null || die "python3 is required"
command -v curl >/dev/null || die "curl is required"

OUT="${OUT:-$SCRIPT_DIR/out/$ENV_NAME-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
SERVICE_LOG="$OUT/service.log"
SERVICE_PID=""

kill_tree() {
	local pid="$1" child
	for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
	kill "$pid" 2>/dev/null || true
}

stop_service() {
	if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
		echo "Stopping ms-clp-blp…"
		# spring-boot:run forks the application JVM: stop the whole tree, not just Maven.
		kill_tree "$SERVICE_PID"
		wait "$SERVICE_PID" 2>/dev/null || true
	fi
}
trap stop_service EXIT
trap 'exit 2' INT TERM

if $START; then
	PORT="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).port or 80)' "$MS_URL")"
	if curl -s -o /dev/null --max-time 2 "http://localhost:$PORT"; then
		die "something is already listening on port $PORT — stop it, or pass --no-start to test it"
	fi
	echo "Starting ms-clp-blp with .env.$ENV_NAME (profile $ENV_NAME); log: $SERVICE_LOG"
	(
		cd "$REPO_ROOT"
		# Same loading as `export $(grep -v '^#' .env | xargs)`: KEY=VALUE lines, one pair of
		# surrounding quotes stripped. Values are exported as-is, never evaluated or printed.
		while IFS= read -r line || [[ -n "$line" ]]; do
			[[ "$line" =~ ^[[:space:]]*# || "$line" != *=* ]] && continue
			key="${line%%=*}"; value="${line#*=}"
			[[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
			if [[ ${#value} -ge 2 && ( "$value" == \"*\" || "$value" == \'*\' ) ]]; then
				value="${value:1:${#value}-2}"
			fi
			export "$key=$value"
		done < "$ENV_FILE"
		exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles="$ENV_NAME"
	) > "$SERVICE_LOG" 2>&1 &
	SERVICE_PID=$!

	echo -n "Waiting for the service"
	for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
		if grep -q "Started MsClpBlpApplication" "$SERVICE_LOG" 2>/dev/null; then
			echo " — up."; break
		fi
		if ! kill -0 "$SERVICE_PID" 2>/dev/null || grep -qE "APPLICATION FAILED TO START|BUILD FAILURE" "$SERVICE_LOG"; then
			echo; tail -40 "$SERVICE_LOG" >&2; die "ms-clp-blp did not start (full log: $SERVICE_LOG)"
		fi
		echo -n "."; sleep 1
	done
	grep -q "Started MsClpBlpApplication" "$SERVICE_LOG" || die "ms-clp-blp did not start within ${STARTUP_TIMEOUT}s"
else
	curl -s -o /dev/null --max-time 5 "$MS_URL" || die "nothing answers on $MS_URL (drop --no-start to start it)"
fi

echo "Comparing $(($(wc -l < "$CSV") - 1)) pages: web-bff $BFF_URL vs ms-clp-blp $MS_URL"
set +e
python3 -I "$SCRIPT_DIR/parity_check.py" --env "$ENV_NAME" --env-file "$ENV_FILE" --csv "$CSV" \
	--bff-url "$BFF_URL" --ms-url "$MS_URL" --brand "$BRAND" --workers "$WORKERS" --out "$OUT"
STATUS=$?
set -e

if [[ -f "$SERVICE_LOG" ]]; then
	# Strip ANSI colours, then count the events that explain most differences.
	sed 's/\x1b\[[0-9;]*m//g' "$SERVICE_LOG" | grep -oE 'event="(UPSTREAM_CALL_FAILED|CATEGORY_UNAVAILABLE|BLOCK_DEGRADED|CIRCUIT_BREAKER_STATE_CHANGED)"' \
		| sort | uniq -c | sed 's/^/  service log: /' || true
fi
echo "Report:  $OUT/report.md"
echo "Details: $OUT/results.json · both payloads per page in $OUT/raw/"
[[ $STATUS -le 1 ]] || exit 2
exit $STATUS
