-- Sprint 0: no application schema yet (that's Sprint 2, per SPEC-BASE.md
-- Section 31/57). This file only exists so the docker-entrypoint-initdb.d
-- mount point is non-empty and MySQL's first-boot init runs cleanly.
--
-- The `veilkeeper` database and `veilkeeper` user are already created
-- automatically by the mysql image via MYSQL_DATABASE/MYSQL_USER env vars
-- (see docker-compose.yml), so nothing further is required here for now.
SELECT 1;
