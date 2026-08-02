#!/bin/bash
# Writes a uniquely-tagged row on one node and polls the other for it,
# reporting real elapsed time rather than assuming replication "just works".
#   ./test-replication.sh <write-host> <write-port> <read-host> <read-port>
set -euo pipefail

if [ $# -ne 4 ]; then
  echo "Usage: $0 <write-host> <write-port> <read-host> <read-port>" >&2
  exit 1
fi
WRITE_HOST=$1 WRITE_PORT=$2 READ_HOST=$3 READ_PORT=$4

cd "$(dirname "$0")"
set -a; source .env; set +a

MARK="repltest-$(date +%s%N)"
START=$(date +%s)

mysql -h "$WRITE_HOST" -P "$WRITE_PORT" -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "
CREATE TABLE IF NOT EXISTS repl_probe (
  id INT PRIMARY KEY AUTO_INCREMENT,
  marker VARCHAR(64) UNIQUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO repl_probe (marker) VALUES ('$MARK');
"

for i in $(seq 1 20); do
  FOUND=$(mysql -h "$READ_HOST" -P "$READ_PORT" -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -N \
    -e "SELECT COUNT(*) FROM repl_probe WHERE marker='$MARK';" 2>/dev/null || echo 0)
  if [ "$FOUND" = "1" ]; then
    echo "OK: $WRITE_HOST:$WRITE_PORT -> $READ_HOST:$READ_PORT replicated '$MARK' in $(($(date +%s) - START))s"
    exit 0
  fi
  sleep 1
done
echo "FAIL: '$MARK' did not reach $READ_HOST:$READ_PORT within 20s" >&2
exit 1
