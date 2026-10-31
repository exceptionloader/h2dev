#!/usr/bin/env sh
# Nightly PostgreSQL backup for the production stack (run from nova/ via cron), e.g.:
#   15 3 * * * cd /opt/nova && ./scripts/backup.sh >> backups/backup.log 2>&1
# Keeps 14 days of compressed dumps. Copy backups/ off the server as well!
set -eu
mkdir -p backups
file="backups/nova-$(date +%Y%m%d-%H%M%S).sql.gz"
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db \
  pg_dump -U nova --no-owner nova | gzip > "$file"
find backups -name 'nova-*.sql.gz' -mtime +14 -delete
echo "backup written: $file"
