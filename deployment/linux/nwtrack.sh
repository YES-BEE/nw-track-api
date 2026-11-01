#!/usr/bin/env bash
set -Eeuo pipefail

ACTION="${1:-start}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"

require_command() { command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 1; }; }
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
env_value() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }
set_env() {
  local key="$1" value="$2" temporary
  temporary="$(mktemp)"
  if [[ -f "$ENV_FILE" ]]; then grep -v "^${key}=" "$ENV_FILE" > "$temporary" || true; fi
  printf '%s=%s\n' "$key" "$value" >> "$temporary"
  install -m 600 "$temporary" "$ENV_FILE"
  rm -f "$temporary"
}
initialize_env() {
  require_command openssl
  if [[ ! -f "$ENV_FILE" ]]; then
    umask 077
    cat > "$ENV_FILE" <<EOF
DB_NAME=nwtrack
DB_USERNAME=nwtrack_app
DB_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET_BASE64=$(openssl rand -base64 48 | tr -d '\n')
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 18)
FACTORY_NAME=NW Track Factory
BIND_ADDRESS=127.0.0.1
ALLOWED_SUBNET=127.0.0.1/32
API_PORT=8080
SWAGGER_ENABLED=false
EOF
    chmod 600 "$ENV_FILE"
    echo "Created protected environment file: $ENV_FILE"
    echo "Retrieve and securely store BOOTSTRAP_ADMIN_PASSWORD before first login."
  fi
}
configure_network() {
  initialize_env
  require_command ip
  local interface address subnet port
  interface="$(ip route show default | awk 'NR==1{print $5}')"
  [[ -n "$interface" ]] || { echo "No default network interface found." >&2; exit 1; }
  address="${BIND_ADDRESS:-$(ip -o -4 addr show dev "$interface" scope global | awk 'NR==1{split($4,a,"/");print a[1}')}"
  subnet="${ALLOWED_SUBNET:-$(ip -o route show dev "$interface" scope link | awk 'NR==1{print $1}')}"
  port="${API_PORT:-8080}"
  [[ -n "$address" && -n "$subnet" ]] || { echo "Could not determine interface address/subnet." >&2; exit 1; }
  set_env BIND_ADDRESS "$address";set_env ALLOWED_SUBNET "$subnet";set_env API_PORT "$port"
  if command -v ufw >/dev/null 2>&1; then
    sudo ufw allow in on "$interface" from "$subnet" to "$address" port "$port" proto tcp comment 'NW Track'
  else
    echo "WARNING: ufw is unavailable; configure the host firewall to allow $subnet -> $address:$port only."
  fi
  echo "Configured NW Track at http://$address:$port for subnet $subnet on $interface"
}
prerequisites() { require_command docker;docker info >/dev/null;docker compose version >/dev/null;[[ -f "$COMPOSE_FILE" ]] || { echo "Missing $COMPOSE_FILE" >&2;exit 1; }; }
wait_for_health() {
  local address port url
  address="$(env_value BIND_ADDRESS)";port="$(env_value API_PORT)";url="http://$address:$port/actuator/health"
  for _ in $(seq 1 60); do curl -fsS "$url" | grep -q '"status":"UP"' && { echo "NW Track is ready: $url";return; };sleep 5;done
  compose logs --tail 100 api;echo "Health check timed out." >&2;exit 1
}

case "$ACTION" in
  install) configure_network;prerequisites;compose up -d --build;wait_for_health ;;
  start) initialize_env;prerequisites;compose up -d;wait_for_health ;;
  stop) prerequisites;compose down ;;
  restart) prerequisites;compose restart;wait_for_health ;;
  status) prerequisites;compose ps ;;
  logs) prerequisites;compose logs --tail 200 -f ;;
  test) require_command curl;curl -fsS "http://$(env_value BIND_ADDRESS):$(env_value API_PORT)/actuator/health";echo ;;
  reset) [[ "${2:-}" == "--confirm-delete" ]] || { echo "Use: $0 reset --confirm-delete" >&2;exit 1; };prerequisites;compose down --volumes;compose up -d --build;wait_for_health ;;
  *) echo "Usage: $0 {install|start|stop|restart|status|logs|test|reset}" >&2;exit 2 ;;
esac
