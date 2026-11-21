#!/usr/bin/env bash
# Local test network: two Paper servers behind two BungeeCord proxies.
#
#   proxy-1 :25577 -> survival-1 :25566
#   proxy-2 :25578 -> lobby-1    :25567 (with Floodgate)
#
# Usage: ./network.sh setup|start|stop|reset
# Requires Java 21, Node 18+, curl and a running `docker compose up -d` in the project root.
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)/.."
WORK="$(pwd)/.work"
PAPER_VERSION="1.21.3"
DATABASE="reports_e2e"

offline_uuid() {
  node -e '
    const crypto = require("crypto");
    const hash = crypto.createHash("md5").update("OfflinePlayer:" + process.argv[1]).digest();
    hash[6] = (hash[6] & 0x0f) | 0x30;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    const hex = hash.toString("hex");
    console.log(`${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`);
  ' "$1"
}

download() {
  mkdir -p "$WORK/downloads"
  cd "$WORK/downloads"
  if [[ ! -f paper.jar ]]; then
    url=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds" \
      | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s)[0].downloads["server:default"].url))')
    curl -fsSL -o paper.jar "$url"
  fi
  [[ -f BungeeCord.jar ]] || curl -fsSL -o BungeeCord.jar \
    "https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar"
  [[ -f floodgate.jar ]] || curl -fsSL -o floodgate.jar \
    "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot"
  cd - > /dev/null
}

setup_server() {
  local name=$1 port=$2 dir="$WORK/$1"
  mkdir -p "$dir/plugins/ReportSystem"
  cp "$WORK/downloads/paper.jar" "$dir/"
  cp "$ROOT"/bukkit/target/ReportSystem-Bukkit-*.jar "$dir/plugins/"
  echo "eula=true" > "$dir/eula.txt"
  cat > "$dir/server.properties" <<PROPERTIES
server-port=$port
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
difficulty=peaceful
spawn-monsters=false
spawn-protection=0
view-distance=3
simulation-distance=3
motd=$name
PROPERTIES
  printf 'settings:\n  bungeecord: true\n' > "$dir/spigot.yml"
  echo "[{\"uuid\":\"$(offline_uuid Moderator)\",\"name\":\"Moderator\",\"level\":4,\"bypassesPlayerLimit\":false}]" > "$dir/ops.json"
  cat > "$dir/plugins/ReportSystem/config.yml" <<CONFIG
server-name: "$name-1"
mongodb:
  uri: "mongodb://localhost:27017"
  database: "$DATABASE"
redis:
  host: "localhost"
  port: 6379
reports:
  cooldown-seconds: 3
  max-open-per-reporter: 5
CONFIG
  touch "$dir/console.txt"
}

setup_proxy() {
  local id=$1 port=$2 server=$3 dir="$WORK/proxy-$1"
  mkdir -p "$dir/plugins/ReportSystem"
  cp "$WORK/downloads/BungeeCord.jar" "$dir/"
  cp "$ROOT"/bungee/target/ReportSystem-Bungee-*.jar "$dir/plugins/"
  cat > "$dir/config.yml" <<CONFIG
online_mode: false
ip_forward: true
connection_throttle: 0
permissions:
  default: [bungeecord.command.server]
  admin: [report.admin]
groups:
  Moderator: [admin]
servers:
  survival:
    motd: survival
    address: localhost:25566
    restricted: false
  lobby:
    motd: lobby
    address: localhost:25567
    restricted: false
listeners:
- host: 0.0.0.0:$port
  query_port: $port
  motd: proxy-$id
  priorities: [$server]
  force_default_server: true
  max_players: 20
  tab_list: GLOBAL_PING
CONFIG
  cat > "$dir/plugins/ReportSystem/config.yml" <<CONFIG
mongodb:
  uri: "mongodb://localhost:27017"
  database: "$DATABASE"
reminder:
  delay-seconds: 1
  cache-seconds: 5
  notify-when-empty: true
CONFIG
  touch "$dir/console.txt"
}

launch() {
  local dir=$1 jar=$2 memory=$3
  (cd "$dir" && setsid bash -c "tail -n 0 -f console.txt | java -Xmx$memory -jar $jar --nogui > output.log 2>&1" &)
}

case "${1:-}" in
  setup)
    download
    setup_server survival 25566
    setup_server lobby 25567
    cp "$WORK/downloads/floodgate.jar" "$WORK/lobby/plugins/"
    setup_proxy 1 25577 survival
    setup_proxy 2 25578 lobby
    npm install --silent
    ;;
  start)
    launch "$WORK/survival" paper.jar 1G
    launch "$WORK/lobby" paper.jar 1G
    launch "$WORK/proxy-1" BungeeCord.jar 384M
    launch "$WORK/proxy-2" BungeeCord.jar 384M
    echo "waiting for the servers..."
    for server in survival lobby; do
      until grep -q "Connected to MongoDB" "$WORK/$server/output.log" 2>/dev/null; do sleep 1; done
    done
    echo "network is up"
    ;;
  stop)
    for server in survival lobby; do echo stop >> "$WORK/$server/console.txt"; done
    for proxy in proxy-1 proxy-2; do echo end >> "$WORK/$proxy/console.txt"; done
    sleep 8
    pkill -f "tail -n 0 -f console.txt" || true
    ;;
  reset)
    # keeps the collections and their indexes, removes templates added by the tests
    docker exec report-mongo mongosh --quiet "$DATABASE" --eval \
      'db.reports.deleteMany({}); db.profiles.deleteMany({}); db.templates.deleteMany({_id: {$nin: ["cheating", "insult", "bugusing", "griefing", "spam", "other"]}})'
    ;;
  *)
    echo "usage: $0 setup|start|stop|reset" >&2
    exit 1
    ;;
esac
