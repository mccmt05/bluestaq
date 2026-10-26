-- psql reads the local runtime secret without interpolating it into shell commands.
\getenv runtime_password DB_APP_PASSWORD
CREATE ROLE notes_app LOGIN PASSWORD :'runtime_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
-- The image creates the database and default schema before running this script.
-- noinspection SqlResolve
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- noinspection SqlResolve
GRANT CONNECT ON DATABASE team_notes TO notes_app;
-- noinspection SqlResolve
GRANT USAGE ON SCHEMA public TO notes_app;
