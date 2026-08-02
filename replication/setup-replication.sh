#!/bin/bash
# Points one node's replication source at another over plain TCP (works from
# any LAN host with a mysql client reachable to both — doesn't need to run
# on either node itself). GTID auto-positioning means there's no binlog
# file/position bookkeeping to get wrong. GET_SOURCE_PUBLIC_KEY=1 is needed
# because the repl user authenticates with the default caching_sha2_password
# plugin, which otherwise refuses a plaintext-password exchange over a
# non-TLS connection — this fetches the source's RSA public key instead of
# requiring a cert setup.
#
# Master-slave: run once, replica <- primary.
#   ./setup-replication.sh "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT" "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT"
#
# Master-master: also run the mirror, primary <- replica.
#   ./setup-replication.sh "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT" "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT"
set -euo pipefail

if [ $# -ne 4 ]; then
  echo "Usage: $0 <target-host> <target-port> <source-host> <source-port>" >&2
  exit 1
fi
TARGET_HOST=$1 TARGET_PORT=$2 SOURCE_HOST=$3 SOURCE_PORT=$4

cd "$(dirname "$0")"
set -a; source .env; set +a

mysql -h "$TARGET_HOST" -P "$TARGET_PORT" -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='$SOURCE_HOST',
  SOURCE_PORT=$SOURCE_PORT,
  SOURCE_USER='$REPL_USER',
  SOURCE_PASSWORD='$REPL_PASSWORD',
  SOURCE_AUTO_POSITION=1,
  GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
"

sleep 2
mysql -h "$TARGET_HOST" -P "$TARGET_PORT" -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW REPLICA STATUS\G" \
  | grep -E "Replica_IO_Running|Replica_SQL_Running|Last_Error"
