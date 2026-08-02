#!/bin/bash
# Dumps the voynich-mysql database and writes a gzipped, timestamped copy to
# the NAS. Run from cron; see README for the crontab line. CIFS is fine as a
# target for writing one flat file sequentially — it's only unsuitable as a
# live InnoDB datadir (see docker-compose.nas.yml).
set -euo pipefail

DEST=/usb1/voynich_mysql_backups
RETENTION_DAYS=14

mkdir -p "$DEST"
STAMP=$(date +%F_%H%M%S)
docker exec voynich-mysql mysqldump --no-tablespaces voynich \
  | gzip > "$DEST/voynich_$STAMP.sql.gz"

find "$DEST" -name 'voynich_*.sql.gz' -mtime "+$RETENTION_DAYS" -delete
