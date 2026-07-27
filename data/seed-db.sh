#!/usr/bin/env bash
#
# Loads the press-monitor database from SQL, without needing the backend to have ever run.
#
# Run it from this directory:  cd data && ./seed-db.sh
#
#   ./seed-db.sh                    load every mysql-init/*.sql into a running MySQL
#   ./seed-db.sh --dump-mentions    snapshot the mention table's rows to mysql-init/04-mentions.sql
#   ./seed-db.sh --export           dump the whole database to a timestamped backup file
#   ./seed-db.sh --help
#
# Why this exists: normally two startup side effects build the database — Hibernate creates
# the tables from the JPA entities, and CompanySeedLoader upserts companies.csv once the app
# is ready. That means you can't get a usable database without booting the backend, which in
# turn wants Ollama and a model pulled. The SQL files under mysql-init/ reproduce both steps,
# and this script applies them to a database that already exists.
#
# On a FRESH volume you don't need this at all: docker-compose mounts data/mysql-init/ at
# /docker-entrypoint-initdb.d, so MySQL runs those files itself on first init. Reach for this
# script when the volume already exists (init scripts only ever run once) or when you're
# pointing at a MySQL that Compose didn't create.
#
# --dump-mentions is the one that has to run against a live database, because mentions are
# collected at runtime rather than checked in: they're whatever Google News returned and the
# classifier labelled. It writes them out as a numbered init file, so they get replayed on the
# next fresh start exactly like the schema and the company list. That turns a `down -v` from
# "lose every classification and pay for the LLM run again" into something cheap. Re-run it
# whenever you want the snapshot to catch up.
#
# Everything it applies is idempotent — CREATE TABLE IF NOT EXISTS, INSERT ... ON DUPLICATE
# KEY UPDATE, and INSERT IGNORE for mentions — so re-running only ever adds what's missing.
set -euo pipefail

# Resolve our own path before changing directory, otherwise a relative invocation like
# ./data/seed-db.sh leaves $0 pointing at nothing once we've cd'd away from the caller's cwd.
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$0")"

CONTAINER="${MYSQL_CONTAINER:-}"
DB="${MYSQL_DATABASE:-pressmonitor}"
DB_USER="${MYSQL_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-rootpw}"

MENTIONS_FILE="mysql-init/04-mentions.sql"

usage() {
    # Print the header block: every comment line from line 2 up to the first line of code.
    # Derived rather than hardcoded so editing the header can't silently leak code into --help.
    local last
    last=$(grep -n -m1 '^[^#]' "$SELF" | cut -d: -f1)
    sed -n "2,$((last - 1))p" "$SELF" | sed 's/^# \{0,1\}//'
    exit 0
}

MODE="load"
case "${1:-}" in
    --dump-mentions) MODE="dump-mentions" ;;
    --export) MODE="export" ;;
    --help|-h) usage ;;
    "") ;;
    *) echo "!! Unknown option: $1 (try --help)" >&2; exit 1 ;;
esac

if ! docker info >/dev/null 2>&1; then
    echo "!! Docker isn't running. Start it and try again." >&2
    exit 1
fi

# Find the MySQL container. Prefer the compose service name, fall back to the image, so this
# works whether or not the stack was started from this directory.
if [[ -z "$CONTAINER" ]]; then
    CONTAINER="$(docker compose ps -q mysql 2>/dev/null || true)"
fi
if [[ -z "$CONTAINER" ]]; then
    CONTAINER="$(docker ps --filter ancestor=mysql:8.4 --format '{{.ID}}' | head -n1)"
fi
if [[ -z "$CONTAINER" ]]; then
    echo "!! Couldn't find a running MySQL container." >&2
    echo "   Start the stack first (./run.sh), or set MYSQL_CONTAINER=<name-or-id>." >&2
    exit 1
fi

echo "==> Using MySQL container ${CONTAINER:0:12}"

# The container is up before MySQL inside it is ready to accept connections; wait rather than
# failing on a connection refused.
echo "==> Waiting for MySQL to accept connections..."
for i in $(seq 1 30); do
    if docker exec "$CONTAINER" mysqladmin ping -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --silent >/dev/null 2>&1; then
        break
    fi
    if [[ $i -eq 30 ]]; then
        echo "!! MySQL didn't become ready in time." >&2
        exit 1
    fi
    sleep 2
done

if [[ "$MODE" == "dump-mentions" ]]; then
    ROWS="$(docker exec -i "$CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -B "$DB" \
            -e 'SELECT COUNT(*) FROM mention' 2>/dev/null || echo 0)"
    if [[ "$ROWS" == "0" ]]; then
        echo "!! The mention table is empty — nothing to snapshot." >&2
        echo "   Collect some coverage first:  curl -X POST http://localhost:8080/api/run" >&2
        exit 1
    fi
    echo "==> Snapshotting $ROWS mention row(s) to $MENTIONS_FILE"

    # Data only. The CREATE TABLE for `mention` lives in 03-schema-and-companies.sql and
    # deliberately isn't repeated here — two copies of the same DDL is exactly how the file
    # and the JPA entity drift apart. 03 sorts before 04, so the table always exists first.
    #
    # --insert-ignore rather than plain INSERT so replaying this is idempotent. A row that
    # collides on the (company_id, dedup_key) unique key is skipped, which is the same rule
    # MonitoringService uses to decide an article is already stored.
    #
    # --complete-insert writes column names into every statement, so the file keeps working
    # if a column is ever added to the entity.
    {
        cat <<EOF
-- Snapshot of the mention table's contents, produced by ./seed-db.sh --dump-mentions.
--
-- Unlike the other files here this one is GENERATED, not authored: mentions are collected at
-- runtime from Google News and labelled by the local model, so they can't be derived from
-- anything in the repo. Re-run --dump-mentions to refresh it.
--
-- Rows only. The mention table itself is created by 03-schema-and-companies.sql, which sorts
-- first and therefore runs first, both here and under /docker-entrypoint-initdb.d.
--
-- INSERT IGNORE makes replay idempotent: a row already present under the same
-- (company_id, dedup_key) is skipped rather than duplicated.
--
-- Snapshot taken: $(date -u '+%Y-%m-%d %H:%M:%SZ')  |  rows: $ROWS

USE $DB;

EOF
        docker exec -i "$CONTAINER" mysqldump \
            -u"$DB_USER" -p"$DB_PASS" \
            --no-create-info \
            --insert-ignore \
            --complete-insert \
            --single-transaction \
            --no-tablespaces \
            --skip-add-locks \
            --skip-comments \
            "$DB" mention
    } > "$MENTIONS_FILE"

    echo "==> Wrote $MENTIONS_FILE ($(wc -l < "$MENTIONS_FILE") lines, $(du -h "$MENTIONS_FILE" | cut -f1))"
    echo "    It's picked up automatically by ./seed-db.sh and on the next fresh volume."
    exit 0
fi

if [[ "$MODE" == "export" ]]; then
    mkdir -p backups
    OUT="backups/press-monitor-$(date +%Y%m%d-%H%M%S).sql"
    echo "==> Dumping '$DB' (schema + all data, mentions and Quartz state included) to $OUT"

    # The redirect happens inside the container and the file is copied out afterwards, rather
    # than piping mysqldump's stdout through the host shell. That costs a temp file but is
    # worth it: on Windows, PowerShell's ">" writes UTF-16 by default, which silently produces
    # a dump the mysql client can't read back. Copying bytes avoids the whole question.
    docker exec "$CONTAINER" sh -c \
        "mysqldump -u'$DB_USER' -p'$DB_PASS' --databases '$DB' \
            --single-transaction --no-tablespaces --skip-add-locks > /tmp/dump.sql"
    docker cp "$CONTAINER:/tmp/dump.sql" "$OUT"
    docker exec "$CONTAINER" rm -f /tmp/dump.sql

    echo "==> Wrote $OUT ($(wc -l < "$OUT") lines, $(du -h "$OUT" | cut -f1))"
    echo
    echo "    Restore into a running MySQL with:"
    echo "      docker exec -i \$(docker compose ps -q mysql) mysql -u$DB_USER -p'<password>' < $OUT"
    echo
    echo "    Note this is a point-in-time backup, not the seed files. To carry collected"
    echo "    mentions across a rebuild instead, use --dump-mentions."
    exit 0
fi

# Apply in filename order: the read-only user, then Quartz's tables, then schema + companies.
shopt -s nullglob
FILES=(mysql-init/*.sql)
if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "!! No .sql files found in mysql-init/" >&2
    exit 1
fi

for f in "${FILES[@]}"; do
    echo "==> Applying $f"
    if ! docker exec -i "$CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" < "$f"; then
        echo "!! Failed while applying $f" >&2
        exit 1
    fi
done

echo "==> Verifying..."
docker exec -i "$CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -B "$DB" <<'SQL'
SELECT CONCAT('companies: ', COUNT(*)) FROM company;
SELECT CONCAT('mentions:  ', COUNT(*)) FROM mention;
SELECT CONCAT('quartz triggers: ', COUNT(*)) FROM QRTZ_TRIGGERS;
SQL

echo
echo "==> Done. The database is populated; the backend is no longer needed to bootstrap it."
echo "    Mentions stay empty until a collection run — trigger one with:"
echo "      curl -X POST http://localhost:8080/api/run"
