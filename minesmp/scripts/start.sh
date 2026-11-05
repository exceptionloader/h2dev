#!/usr/bin/env bash
# MineSMP Start-Skript mit Aikar-Flags und automatischem Neustart.
# RAM ueber Umgebungsvariable: MEMORY=10G ./start.sh   - Java 25 erforderlich (WorldEdit/WorldGuard)
# Anderes Java: JAVA=/pfad/zu/java ./start.sh
cd "$(dirname "$0")"
MEMORY="${MEMORY:-8G}"
while true; do
  "${JAVA:-java}" -Xms"$MEMORY" -Xmx"$MEMORY" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
    -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 \
    -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 \
    -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
    -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true -jar paper.jar --nogui
  [ -f .no-restart ] && { rm -f .no-restart; break; }
  echo "Server gestoppt - Neustart in 10 Sekunden (STRG+C zum Abbrechen)..."
  sleep 10
done
