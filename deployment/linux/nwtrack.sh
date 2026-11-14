#!/usr/bin/env bash
set -Eeuo pipefail

ACTION="${1:-start}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
CERT_DIR="$ROOT_DIR/deployment/certificates"
APP_DOMAIN_DEFAULT="nowash-track.com"

require_command() { command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 1; }; }
compose() { NGINX_TEMPLATE=default.conf.template COMPOSE_PROFILES=https ALLOWED_ORIGINS="https://$(env_value APP_DOMAIN)" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
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
APP_DOMAIN=$APP_DOMAIN_DEFAULT
BIND_ADDRESS=127.0.0.1
ALLOWED_SUBNET=127.0.0.1/32
SWAGGER_ENABLED=true
EOF
    chmod 600 "$ENV_FILE"
    echo "Created protected environment file: $ENV_FILE"
    echo "Retrieve and securely store BOOTSTRAP_ADMIN_PASSWORD before first login."
  fi
  [[ -n "$(env_value APP_DOMAIN)" ]] || set_env APP_DOMAIN "$APP_DOMAIN_DEFAULT"
  [[ -n "$(env_value BIND_ADDRESS)" ]] || set_env BIND_ADDRESS "127.0.0.1"
  [[ -n "$(env_value ALLOWED_SUBNET)" ]] || set_env ALLOWED_SUBNET "127.0.0.1/32"
  [[ -n "$(env_value SWAGGER_ENABLED)" ]] || set_env SWAGGER_ENABLED "false"
}
configure_network() {
  initialize_env
  require_command ip
  local interface address subnet
  interface="$(ip route show default | awk 'NR==1{print $5}')"
  [[ -n "$interface" ]] || { echo "No default network interface found." >&2; exit 1; }
  address="${BIND_ADDRESS:-$(ip -o -4 addr show dev "$interface" scope global | awk 'NR==1{split($4,a,"/");print a[1]}')}"
  subnet="${ALLOWED_SUBNET:-$(ip -o route show dev "$interface" scope link | awk 'NR==1{print $1}')}"
  [[ -n "$address" && -n "$subnet" ]] || { echo "Could not determine interface address/subnet." >&2; exit 1; }
  set_env BIND_ADDRESS "$address";set_env ALLOWED_SUBNET "$subnet";set_env APP_DOMAIN "$APP_DOMAIN_DEFAULT"
  if command -v ufw >/dev/null 2>&1; then
    sudo ufw allow in on "$interface" from "$subnet" to "$address" port 443 proto tcp comment 'NW Track HTTPS'
    sudo ufw allow in on "$interface" from "$subnet" to "$address" port 80 proto tcp comment 'NW Track redirect'
    sudo ufw allow in on "$interface" from "$subnet" to "$address" port 53 proto tcp comment 'NW Track DNS TCP'
    sudo ufw allow in on "$interface" from "$subnet" to "$address" port 53 proto udp comment 'NW Track DNS UDP'
  else
    echo "WARNING: ufw is unavailable; allow $subnet to TCP 80/443 and TCP+UDP 53."
  fi
  echo "Configured https://$APP_DOMAIN_DEFAULT for subnet $subnet on $interface"
  echo "Set the Wi-Fi router DHCP DNS server to $address. Do not configure port forwarding."
}
prerequisites() { require_command docker;docker info >/dev/null;docker compose version >/dev/null;[[ -f "$COMPOSE_FILE" ]] || { echo "Missing $COMPOSE_FILE" >&2;exit 1; }; }
ensure_certificates() {
  local domain mount
  domain="$(env_value APP_DOMAIN)";domain="${domain:-$APP_DOMAIN_DEFAULT}"
  [[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "Invalid APP_DOMAIN: $domain" >&2;exit 1; }
  mkdir -p "$CERT_DIR";chmod 700 "$CERT_DIR"
  cat > "$CERT_DIR/openssl.cnf" <<EOF
[req]
distinguished_name=dn
prompt=no
[dn]
CN=$domain
[v3_server]
subjectAltName=DNS:$domain
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
basicConstraints=critical,CA:FALSE
EOF
  if [[ -s "$CERT_DIR/server.crt" ]] && ! docker run --rm --entrypoint openssl -v "$CERT_DIR:/certs" alpine/openssl:3.5.4 x509 -in /certs/server.crt -noout -checkhost "$domain" >/dev/null 2>&1; then
    rm -f "$CERT_DIR/server.crt" "$CERT_DIR/server.key"
    echo "Configured hostname changed; regenerating the server certificate while preserving the factory CA."
  fi
  if [[ ! -s "$CERT_DIR/ca.crt" || ! -s "$CERT_DIR/ca.key" || ! -s "$CERT_DIR/server.crt" || ! -s "$CERT_DIR/server.key" ]]; then
    mount="$CERT_DIR:/certs"
    docker run --rm --entrypoint sh -v "$mount" alpine/openssl:3.5.4 -c "set -eu;cd /certs;if [ ! -s ca.key ] || [ ! -s ca.crt ];then openssl genrsa -out ca.key 4096;openssl req -x509 -new -sha256 -days 3650 -key ca.key -subj '/CN=NW Track Factory Root CA' -out ca.crt;fi;if [ ! -s server.key ] || [ ! -s server.crt ];then openssl genrsa -out server.key 3072;openssl req -new -key server.key -config openssl.cnf -out server.csr;openssl x509 -req -sha256 -days 825 -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -extfile openssl.cnf -extensions v3_server -out server.crt;rm -f server.csr ca.srl;fi;chmod 600 ca.key server.key"
    echo "Generated internal HTTPS certificate for $domain."
  fi
  if command -v update-ca-certificates >/dev/null 2>&1; then
    sudo install -m 644 "$CERT_DIR/ca.crt" /usr/local/share/ca-certificates/nwtrack-factory-ca.crt
    sudo update-ca-certificates >/dev/null
  fi
  echo "Install $CERT_DIR/ca.crt as a trusted root CA on every client device."
}
wait_for_health() {
  local address domain url
  address="$(env_value BIND_ADDRESS)";domain="$(env_value APP_DOMAIN)";url="https://$domain/actuator/health"
  for _ in $(seq 1 60); do
    local response
    if response="$(curl -fsS --connect-timeout 5 --max-time 10 --cacert "$CERT_DIR/ca.crt" --resolve "$domain:443:$address" "$url" 2>/dev/null)" && grep -q '"status":"UP"' <<<"$response"; then
      echo "NW Track is ready: $url"
      return
    fi
    sleep 5
  done
  echo "Health check timed out: $url" >&2
  compose ps >&2
  compose logs --tail 100 nginx api >&2
  exit 1
}
start_compose() {
  local build_flag="${1:-}"
  if ! compose up -d $build_flag; then
    echo "Existing containers or networks require reconciliation; database volumes will be preserved." >&2
    compose down --remove-orphans
    compose up -d $build_flag
  fi
}

case "$ACTION" in
  install) configure_network;prerequisites;ensure_certificates;start_compose --build;wait_for_health ;;
  start) initialize_env;prerequisites;ensure_certificates;start_compose;wait_for_health ;;
  stop) prerequisites;compose down ;;
  restart) prerequisites;compose restart;wait_for_health ;;
  status) prerequisites;compose ps ;;
  logs) prerequisites;compose logs --tail 200 -f ;;
  certificate) initialize_env;prerequisites;rm -f "$CERT_DIR/server.crt" "$CERT_DIR/server.key";ensure_certificates;compose up -d --force-recreate nginx ;;
  test) require_command curl;curl -fsS --cacert "$CERT_DIR/ca.crt" --resolve "$(env_value APP_DOMAIN):443:$(env_value BIND_ADDRESS)" "https://$(env_value APP_DOMAIN)/actuator/health";echo ;;
  reset) [[ "${2:-}" == "--confirm-delete" ]] || { echo "Use: $0 reset --confirm-delete" >&2;exit 1; };prerequisites;compose down --volumes;compose up -d --build;wait_for_health ;;
  *) echo "Usage: $0 {install|start|stop|restart|status|logs|certificate|test|reset}" >&2;exit 2 ;;
esac
