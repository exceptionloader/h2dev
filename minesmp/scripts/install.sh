#!/usr/bin/env bash
# =====================================================================
#  MineSMP - Installationsskript (Linux)
#  Laedt Paper 26.3 + alle Plugins, baut MineSMPCore und kopiert die Konfiguration.
#  Benutzung:  ./scripts/install.sh [Zielordner]   (Standard: ./live)
#  Voraussetzungen: Java 25 (Server), JDK 21+ und Maven 3.9+ (Bauen), curl, python3
# =====================================================================
set -euo pipefail
MC_VERSION="26.3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-$ROOT/live}"
mkdir -p "$TARGET/plugins" "$TARGET/config"
cd "$TARGET"

need() { command -v "$1" >/dev/null || { echo "FEHLER: '$1' fehlt."; exit 1; }; }
need java; need curl; need python3; need mvn
JV=$(java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/{gsub(/ /,"",$2);print $2}')
if [ "${JV%%.*}" -lt 25 ] 2>/dev/null; then
  echo "WARNUNG: Java $JV gefunden. Minecraft $MC_VERSION (und WorldEdit/WorldGuard) brauchen Java 25 zum Starten des Servers."
fi

echo ">> Paper $MC_VERSION herunterladen"
PAPER_URL=$(curl -fsS "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['downloads']['server:default']['url'])")
curl -fsSL -o paper.jar "$PAPER_URL"

modrinth() { # slug, dateiname
  local url
  url=$(curl -fsS "https://api.modrinth.com/v2/project/$1/version?game_versions=%5B%22$MC_VERSION%22%5D&loaders=%5B%22paper%22%5D" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);r=[v for v in d if v['version_type']=='release'] or d;print(r[0]['files'][0]['url'] if r else '')")
  if [ -z "$url" ]; then echo "   WARNUNG: $1 hat keine Version fuer $MC_VERSION auf Modrinth - bitte manuell pruefen!"; return; fi
  curl -fsSL -o "plugins/$2" "$url" && echo "   $2"
}

echo ">> Plugins herunterladen"
modrinth luckperms LuckPerms.jar
modrinth placeholderapi PlaceholderAPI.jar
modrinth worldedit WorldEdit.jar
modrinth worldguard WorldGuard.jar
modrinth coreprotect CoreProtect.jar
modrinth grimac GrimAC.jar
modrinth gsit GSit.jar
modrinth chunky Chunky.jar
# 3D-Modelle fuer NPCs und Bosse (fest auf 3.6.0, dazu passt MineSMPCore) mit Pruefsumme
curl -fsSL -o plugins/BetterModel.jar https://cdn.modrinth.com/data/4h8rX3rt/versions/fToyVqvi/bettermodel-3.6.0-paper.jar \
  && echo "b8048f06900a06e4fddb47c7add5af4ae075579a66e29eb29f5e0824c273ad12df57b06afac4f9c6931554e611179b5faa698f36952090ddbd66ae7bfef4d212  plugins/BetterModel.jar" | sha512sum -c --quiet - && echo "   BetterModel.jar (3.6.0)" \
  || { rm -f plugins/BetterModel.jar; echo "   WARNUNG: BetterModel nicht geladen (Pruefsumme) - NPCs/Bosse ohne 3D-Modell"; }
curl -fsSL -o plugins/Vault.jar https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar && echo "   Vault.jar"
if [ "${BEDROCK:-1}" = "1" ]; then
  curl -fsSL -o plugins/Geyser-Spigot.jar https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot && echo "   Geyser-Spigot.jar"
  curl -fsSL -o plugins/Floodgate-Spigot.jar https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot && echo "   Floodgate-Spigot.jar"
fi
echo "   Hinweis: NuVotifier (Vote-Belohnungen) bitte manuell von https://github.com/NuVotifier/NuVotifier/releases laden."

echo ">> MineSMPCore bauen"
(cd "$ROOT/core" && mvn -q -B clean package)
cp "$ROOT"/core/target/MineSMPCore-*.jar plugins/

echo ">> Konfiguration kopieren (vorhandene Dateien werden NICHT ueberschrieben)"
for f in server.properties bukkit.yml spigot.yml; do [ -f "$f" ] || cp "$ROOT/server/$f" .; done
for f in "$ROOT"/server/config/*.yml; do [ -f "config/$(basename "$f")" ] || cp "$f" config/; done
cp "$ROOT/scripts/start.sh" "$ROOT/scripts/backup.sh" . && chmod +x start.sh backup.sh
[ -f eula.txt ] || { echo "Bitte Minecraft-EULA lesen: https://aka.ms/MinecraftEULA"; echo "eula=false" > eula.txt; }

echo
echo "Fertig! Naechste Schritte:"
echo " 1. eula.txt auf eula=true setzen (nach Lesen der EULA)"
echo " 2. ./start.sh  (erster Start erzeugt Welten und Plugin-Ordner)"
echo " 3. In der Konsole die Befehle aus scripts/luckperms-setup.txt und scripts/worldguard-setup.txt ausfuehren"
echo " 4. Siehe docs/INSTALL.md"
