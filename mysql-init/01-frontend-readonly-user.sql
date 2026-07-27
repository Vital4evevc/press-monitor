-- Creates a separate, read-only MySQL user for press-monitor-frontend. The backend keeps
-- using the full read/write 'pressmonitor' user (created via docker-compose.yml's
-- MYSQL_USER/MYSQL_PASSWORD) since it owns the schema and writes companies/mentions;
-- the frontend only ever reads, so it connects as this user instead.
--
-- NOTE: scripts under /docker-entrypoint-initdb.d only run once, the first time the MySQL
-- data directory is initialized. If you already have an existing mysql-data volume from
-- before this user existed, it won't be picked up automatically — either
--   docker compose down -v   (wipes the volume, all data is reseeded on next start), or
-- run this file's statements by hand against the running mysql container.
--
-- Credentials here must match SPRING_DATASOURCE_USERNAME/PASSWORD for the frontend service
-- in docker-compose.yml (FRONTEND_DB_USERNAME/FRONTEND_DB_PASSWORD). Fine for local/demo use
-- like the rest of this compose file's hardcoded passwords — rotate for anything real.
--
-- Granted at the schema level (pressmonitor.*), not per-table: this script runs during
-- MySQL's own first-time initialization, before the backend has even started, let alone
-- had Hibernate create the company/mention tables — a table-level GRANT would fail with
-- "table doesn't exist" at this point. A schema-level SELECT grant applies to every table
-- in `pressmonitor`, present or future, without requiring any of them to exist yet.

CREATE USER IF NOT EXISTS 'pressmonitor_ro'@'%' IDENTIFIED BY 'pressmonitor_ro';
GRANT SELECT ON pressmonitor.* TO 'pressmonitor_ro'@'%';
FLUSH PRIVILEGES;
