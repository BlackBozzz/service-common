-- Роли. Права роли хранятся JSON-документом grants: {"tables": {...}, "actions": [...]}.
CREATE TABLE common_role
(
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    system      BOOLEAN       NOT NULL DEFAULT FALSE,
    grants      JSONB         NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT uk_common_role_code
        UNIQUE (code)
);

-- Роли пользователей.
CREATE TABLE common_user_role
(
    user_id UUID   NOT NULL,
    role_id UUID   NOT NULL,

    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_common_user_role_user
        FOREIGN KEY (user_id) REFERENCES common_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_common_user_role_role
        FOREIGN KEY (role_id) REFERENCES common_role (id) ON DELETE CASCADE
);
