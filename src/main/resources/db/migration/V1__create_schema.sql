CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL
);

CREATE TABLE team (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);

CREATE TABLE membership (
    team_id UUID NOT NULL REFERENCES team(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (team_id, user_id)
);

CREATE INDEX membership_user_idx ON membership (user_id, team_id);

CREATE TABLE note (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES team(id),
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    content VARCHAR(20000) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX note_team_updated_idx ON note (team_id, updated_at DESC, id DESC);
