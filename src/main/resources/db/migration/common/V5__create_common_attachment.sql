-- Вложения к записям любых таблиц приложения; привязка строками, без FK.
CREATE TABLE common_attachment
(
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type    VARCHAR(63)  NOT NULL,
    entity_id      VARCHAR(255) NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(255) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    storage_key    VARCHAR(255) NOT NULL,
    sha256         CHAR(64)     NOT NULL,
    uploaded_by    UUID,
    uploaded_login VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_common_attachment_storage_key UNIQUE (storage_key)
);
CREATE INDEX ix_common_attachment_entity ON common_attachment (entity_type, entity_id, created_at);
