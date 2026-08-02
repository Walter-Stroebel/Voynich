#!/bin/bash
# Runs once, on first container start (official mysql image convention:
# anything in /docker-entrypoint-initdb.d executes against a fresh volume
# only). Mounted identically on both the primary and replica compose files
# because master-master needs each side to be able to authenticate the
# other as a replication source.
set -euo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
CREATE USER IF NOT EXISTS '${REPL_USER}'@'%' IDENTIFIED BY '${REPL_PASSWORD}';
GRANT REPLICATION SLAVE ON *.* TO '${REPL_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
