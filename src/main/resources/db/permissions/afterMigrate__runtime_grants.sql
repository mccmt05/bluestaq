-- Loaded explicitly by Compose, after the owner has migrated and optionally seeded the database.
-- Runtime code can read identities/memberships and manage notes, but cannot provision identities or run DDL.
GRANT SELECT ON app_user, team, membership TO notes_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON note TO notes_app;
