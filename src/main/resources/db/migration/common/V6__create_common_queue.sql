-- Argent Queue: независимые очереди, надёжные задания и история попыток.
-- Миграция добавляется новой версией; ранее применённые V1–V5 не изменяются.
CREATE TABLE argent_queue (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
    allow_skip BOOLEAN NOT NULL DEFAULT TRUE,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    lock_token UUID,
    locked_by VARCHAR(160),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT argent_queue_name_not_blank CHECK (length(trim(name)) > 0)
);

CREATE TABLE argent_queue_task (
    id UUID PRIMARY KEY,
    queue_id UUID NOT NULL REFERENCES argent_queue(id) ON DELETE RESTRICT,
    sequence_no BIGINT GENERATED ALWAYS AS IDENTITY,
    handler_key VARCHAR(160) NOT NULL,
    payload_json TEXT NOT NULL,
    payload_version INTEGER NOT NULL DEFAULT 1,
    priority INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    idempotency_key VARCHAR(200),
    CONSTRAINT argent_queue_task_status CHECK (status IN ('PENDING','RUNNING','RETRY_WAIT','SUCCESS','PAUSED','CANCELLED')),
    CONSTRAINT argent_queue_task_key_unique UNIQUE (queue_id, idempotency_key)
);
CREATE INDEX argent_queue_task_ready_idx ON argent_queue_task(queue_id, status, next_run_at, priority DESC);
CREATE INDEX argent_queue_task_order_idx ON argent_queue_task(queue_id, sequence_no) WHERE status IN ('PENDING','RUNNING','RETRY_WAIT','PAUSED');
CREATE INDEX argent_queue_task_created_idx ON argent_queue_task(queue_id, sequence_no DESC);

CREATE TABLE argent_queue_task_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES argent_queue_task(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    result VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    error_type VARCHAR(300),
    error_message VARCHAR(2000),
    stack_trace TEXT,
    CONSTRAINT argent_queue_attempt_result CHECK (result IN ('RUNNING','SUCCESS','FAILED')),
    CONSTRAINT argent_queue_attempt_unique UNIQUE (task_id, attempt_no)
);
CREATE INDEX argent_queue_attempt_history_idx ON argent_queue_task_attempt(task_id, attempt_no DESC);
