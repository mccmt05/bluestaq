-- These credentials are intentionally public and are loaded only by the local profile.
-- All three accounts use demo-password; only BCrypt hashes are stored.
INSERT INTO app_user (id, username, password_hash) VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'alice', '$2a$10$tjtHoF80ER/rF/gPJ62WheGnNLXgJBBpVE/P8xzhiT0cyYEseR3O.'),
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2', 'bob', '$2a$10$tjtHoF80ER/rF/gPJ62WheGnNLXgJBBpVE/P8xzhiT0cyYEseR3O.'),
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3', 'carol', '$2a$10$tjtHoF80ER/rF/gPJ62WheGnNLXgJBBpVE/P8xzhiT0cyYEseR3O.');

INSERT INTO team (id, name) VALUES
    ('11111111-1111-4111-8111-111111111111', 'Platform'),
    ('22222222-2222-4222-8222-222222222222', 'Research');

INSERT INTO membership (team_id, user_id) VALUES
    ('11111111-1111-4111-8111-111111111111', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'),
    ('11111111-1111-4111-8111-111111111111', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'),
    ('22222222-2222-4222-8222-222222222222', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'),
    ('22222222-2222-4222-8222-222222222222', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3');
