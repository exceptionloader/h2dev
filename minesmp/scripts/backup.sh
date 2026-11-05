#!/usr/bin/env bash
# MineSMP Backup: Welten, Plugin-Daten (inkl. SQLite) und optional MariaDB-Dump. Behaelt die letzten N Backups.
# Cron-Beispiel (alle 6 Stunden):  0 */6 * * * /pfad/zu/live/backup.sh >> /pfad/zu/live/backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")"
DEST="${BACKUP_DIR:-./backups}"
KEEP="${KEEP:-12}"
STAMP=$(date +%Y-%m-%d_%H-%M)
mkdir -p "$DEST"
# SQLite konsistent sichern: WAL-Dateien mitnehmen (Server schreibt im WAL-Modus)
ITEMS=(world world_nether world_the_end spawn plugins server.properties bukkit.yml spigot.yml config)
EXISTING=(); for i in "${ITEMS[@]}"; do [ -e "$i" ] && EXISTING+=("$i"); done
tar --exclude='plugins/*.jar' --exclude='plugins/*/libs' --exclude='plugins/.paper-remapped' -czf "$DEST/minesmp_$STAMP.tar.gz" "${EXISTING[@]}"
if [ -n "${MARIADB_DB:-}" ]; then
  mysqldump --single-transaction -u"${MARIADB_USER:-minesmp}" -p"${MARIADB_PASSWORD:-}" "$MARIADB_DB" | gzip > "$DEST/minesmp_db_$STAMP.sql.gz"
fi
ls -1t "$DEST"/minesmp_*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
ls -1t "$DEST"/minesmp_db_*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "[$STAMP] Backup erstellt: $DEST/minesmp_$STAMP.tar.gz"
