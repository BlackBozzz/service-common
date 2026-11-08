-- Пользователи библиотеки.
CREATE TABLE common_user
(
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    login         VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_common_user_login
        UNIQUE (login)
);

-- Сессии пользователей.
CREATE TABLE common_user_session
(
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID      NOT NULL,
    revoked    BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_common_user_session_user
        FOREIGN KEY (user_id) REFERENCES common_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_common_user_session_user_id ON common_user_session (user_id);
