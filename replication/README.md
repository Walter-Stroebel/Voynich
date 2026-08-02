# MySQL replication (mach1 ↔ mach2)

GTID-based MySQL replication between two Docker hosts, covering both
master-slave and master-master. This is `mach1`/`mach2` test config 3 from
the main `README.md`: mach2 gets Docker (easy to tear down — `docker compose
down -v` and it's gone) rather than nothing at all, and acts as `voynich`'s
replica.

Separate from the repo-root `docker-compose.yml`/`.env` (the plain
single-node "config 2" story) — different container names, different
volumes, different default ports (`13306`/`13307` here vs. `13306` there),
so the two don't collide even run on the same host.

## Layout

- `docker-compose.primary.yml` — mach1 side: `server-id=1`,
  `auto-increment-offset=1`.
- `docker-compose.replica.yml` — mach2 side: `server-id=2`,
  `auto-increment-offset=2`.
- `init/setup-repl-user.sh` — mounted into `/docker-entrypoint-initdb.d` on
  *both* sides (master-master needs each node to authenticate the other as a
  replication source, so both need the account, not just the replica).
- `setup-replication.sh <target-host> <target-port> <source-host> <source-port>`
  — points `target`'s replication source at `source`, over plain TCP, from
  anywhere on the LAN. GTID auto-positioning means no binlog file/position
  bookkeeping.
- `test-replication.sh <write-host> <write-port> <read-host> <read-port>` —
  writes a uniquely-tagged row, polls the other side for it, reports real
  elapsed seconds (not a guess).

`auto-increment-increment=2` on both sides plus opposite offsets (1 vs. 2) is
what makes master-master safe: concurrent `AUTO_INCREMENT` inserts on each
side land on disjoint id sequences (odd on mach1, even on mach2), so there's
no primary-key collision to reconcile. It's a no-op under plain master-slave,
which is why the same compose files serve both scenarios.

## Setup

```bash
cp .env.example .env   # fill in real passwords + IPs, chmod 600
docker compose -f docker-compose.primary.yml --env-file .env up -d   # on mach1
docker compose -f docker-compose.replica.yml --env-file .env up -d   # on mach2

# Master-slave: mach2 replicates from mach1
./setup-replication.sh "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT" "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT"
./test-replication.sh  "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT" "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT"

# Master-master: also point mach1 back at mach2
./setup-replication.sh "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT" "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT"
./test-replication.sh  "$REPL_REPLICA_HOST" "$REPL_REPLICA_PORT" "$REPL_PRIMARY_HOST" "$REPL_PRIMARY_PORT"
```

Verified 2026-08-02 between the real mach1/mach2 (192.168.2.12 ↔ 192.168.2.23):
master-slave replicated a row in 2s; the reverse master-master direction in
under 1s; a genuinely concurrent insert on both sides at once landed as
distinct odd/even ids (no collision) and converged identically on both
nodes.

### Gotcha: `caching_sha2_password` needs `GET_SOURCE_PUBLIC_KEY=1`

`mysql:8` now pulls MySQL 8.4, which dropped the `mysql_native_password`
plugin — the repl user is created with the default `caching_sha2_password`,
which refuses a plaintext-password exchange over a non-TLS connection unless
the client can fetch the source's RSA public key. `setup-replication.sh`
already passes `GET_SOURCE_PUBLIC_KEY=1` in `CHANGE REPLICATION SOURCE TO`
for this reason — no cert setup needed. If you hand-run `CHANGE REPLICATION
SOURCE TO` yourself and see `Last_IO_Errno: 2061 ... Authentication requires
secure connection`, this is why.

## Teardown

```bash
docker compose -f docker-compose.primary.yml --env-file .env down -v   # mach1
docker compose -f docker-compose.replica.yml --env-file .env down -v   # mach2
```
