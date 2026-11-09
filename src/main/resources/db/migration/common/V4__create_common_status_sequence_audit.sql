-- Типы статусов (например, ORDER_STATUS). Библиотечный справочник.
CREATE TABLE common_status_type
(
    id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,

    CONSTRAINT uk_common_status_type_code
        UNIQUE (code)
);

-- Статусы внутри типа. Библиотечный справочник.
CREATE TABLE common_status
(
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    status_type_id UUID         NOT NULL,
    code           VARCHAR(100) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    sort_order     INTEGER      NOT NULL DEFAULT 0,
    is_initial     BOOLEAN      NOT NULL DEFAULT FALSE,
    is_final       BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_common_status_type_code_pair
        UNIQUE (status_type_id, code),
    CONSTRAINT fk_common_status_type
        FOREIGN KEY (status_type_id) REFERENCES common_status_type (id) ON DELETE CASCADE
);

-- Допустимые переходы между статусами. from_status_id = NULL означает создание записи.
CREATE TABLE common_status_transition
(
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    status_type_id  UUID         NOT NULL,
    from_status_id  UUID,
    to_status_id    UUID         NOT NULL,
    required_action VARCHAR(200),

    CONSTRAINT uk_common_status_transition
        UNIQUE (status_type_id, from_status_id, to_status_id),
    CONSTRAINT fk_common_status_transition_type
        FOREIGN KEY (status_type_id) REFERENCES common_status_type (id) ON DELETE CASCADE,
    CONSTRAINT fk_common_status_transition_from
        FOREIGN KEY (from_status_id) REFERENCES common_status (id) ON DELETE CASCADE,
    CONSTRAINT fk_common_status_transition_to
        FOREIGN KEY (to_status_id) REFERENCES common_status (id) ON DELETE CASCADE
);

-- PostgreSQL считает NULL различными в UNIQUE: отдельно запрещаем дубли переходов «создание → статус».
CREATE UNIQUE INDEX uk_common_status_transition_initial
    ON common_status_transition (status_type_id, to_status_id)
    WHERE from_status_id IS NULL;

-- Счётчики номеров документов. Библиотечный справочник.
CREATE TABLE common_sequence
(
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(100) NOT NULL,
    pattern      VARCHAR(255) NOT NULL,
    reset_period VARCHAR(20)  NOT NULL,
    period_key   VARCHAR(50),
    counter      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_common_sequence_code
        UNIQUE (code),
    CONSTRAINT ck_common_sequence_reset_period
        CHECK (reset_period IN ('NEVER', 'YEARLY', 'MONTHLY', 'WEEKLY', 'DAILY'))
);

-- Журнал изменений сущностей с @Audited.
CREATE TABLE common_audit_entry
(
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(255) NOT NULL,
    entity_id   TEXT,
    action      VARCHAR(10)  NOT NULL,
    field       VARCHAR(255),
    old_value   TEXT,
    new_value   TEXT,
    actor_id    UUID,
    actor_login VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_common_audit_entry_entity
    ON common_audit_entry (entity_type, entity_id, created_at);

-- Библиотечные справочники регистрируются миграцией: регистрация при запуске
-- находит их в реестре и не пытается создать таблицы повторно.
INSERT INTO common_dictionaries (code, name, entity_class, dto_class, generated_at)
VALUES ('common_status_type', 'Типы статусов', 'CommonStatusType',
        'com.argent.servicecommon.status.dto.CommonStatusTypeDto', (now() AT TIME ZONE 'UTC')),
       ('common_status', 'Статусы', 'CommonStatus',
        'com.argent.servicecommon.status.dto.CommonStatusDto', (now() AT TIME ZONE 'UTC')),
       ('common_status_transition', 'Переходы статусов', 'CommonStatusTransition',
        'com.argent.servicecommon.status.dto.CommonStatusTransitionDto', (now() AT TIME ZONE 'UTC')),
       ('common_sequence', 'Счётчики номеров', 'CommonSequence',
        'com.argent.servicecommon.sequence.dto.CommonSequenceDto', (now() AT TIME ZONE 'UTC'));
