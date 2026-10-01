-- 05_jobs.sql — SYP, PostgreSQL 16
--
-- Очередь заданий и реестр артефактов.
-- Спецификация: FR-003, FR-004, FR-088, FR-091, FR-092.
-- constitution IV: состояние задания наблюдаемо, код возврата внешней
-- программы проверяется всегда, запись артефакта атомарна.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.17–2.18.
-- Миграция добавочная, применяется один раз после 04_selection.sql.

CREATE TABLE job (
    id                BIGSERIAL      PRIMARY KEY,
    kind              TEXT           NOT NULL,
    state             TEXT           NOT NULL DEFAULT 'WAITING',
    subject_type      TEXT,
    subject_id        BIGINT,
    params            JSONB          NOT NULL,
    params_hash       TEXT           NOT NULL,
    algorithm_version TEXT,
    progress_done     BIGINT         NOT NULL DEFAULT 0,
    progress_total    BIGINT         NOT NULL DEFAULT 0,
    progress_note     TEXT,
    error_text        TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,

    CONSTRAINT job_kind_known      CHECK (kind IN ('ANALYZE', 'FACES', 'TRAIN', 'ASSEMBLE')),
    -- Ровно этот набор состояний, что и в правилах очереди проекта.
    CONSTRAINT job_state_known     CHECK (state IN ('WAITING', 'CREATING', 'WORKING', 'DONE', 'ERROR')),
    CONSTRAINT job_params_not_null CHECK (params IS NOT NULL),
    CONSTRAINT job_hash_not_blank  CHECK (btrim(params_hash) <> ''),
    CONSTRAINT job_progress_nonneg CHECK (progress_done >= 0 AND progress_total >= 0),
    CONSTRAINT job_done_total_set  CHECK (state <> 'DONE' OR progress_total > 0),
    -- Ненулевой код завершения — ошибка задания с текстом (FR-092).
    CONSTRAINT job_error_only_on_error CHECK (state <> 'ERROR' OR error_text IS NOT NULL),
    CONSTRAINT job_time_order CHECK (
        finished_at IS NULL OR created_at IS NULL OR finished_at >= created_at
    )
);

COMMENT ON TABLE job IS
    'Единица работы в очереди. Состояние DONE означает, что все внешние программы завершились с нулевым кодом (FR-004, SC-005).';
COMMENT ON COLUMN job.params_hash IS
    'Хеш входных параметров: по нему воркер понимает, можно ли пропустить уже выполненную работу (Р-10).';
COMMENT ON COLUMN job.progress_note IS
    'Что задание делает сейчас: фаза, кадр, этап обучения.';

-- Выборка задания воркером одним оператором: CREATING означает «взято в работу».
CREATE INDEX job_pick_idx       ON job (created_at) WHERE state = 'WAITING';
CREATE INDEX job_state_idx      ON job (state, created_at);
CREATE INDEX job_subject_idx    ON job (subject_type, subject_id) WHERE subject_id IS NOT NULL;
CREATE INDEX job_params_hash_idx ON job (kind, params_hash);

CREATE TABLE artifact (
    id             BIGSERIAL   PRIMARY KEY,
    job_id         BIGINT
        REFERENCES job (id) ON DELETE SET NULL,
    kind           TEXT        NOT NULL,
    placement      TEXT        NOT NULL,
    object_key     TEXT        NOT NULL,
    content_type   TEXT,
    byte_size      BIGINT,
    checksum       TEXT,
    state          TEXT        NOT NULL DEFAULT 'WRITING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT artifact_kind_known      CHECK (kind IN ('PREVIEW_SHEET', 'MODEL', 'CUT_FRAGMENT', 'ASSEMBLY')),
    -- Д-8: превью и промежуточные файлы — SSD, собранные видеофайлы — HDD.
    CONSTRAINT artifact_placement_known CHECK (placement IN ('SSD', 'HDD')),
    CONSTRAINT artifact_state_known     CHECK (state IN ('WRITING', 'READY', 'FAILED')),
    CONSTRAINT artifact_key_not_blank   CHECK (btrim(object_key) <> ''),
    CONSTRAINT artifact_kind_key_unique UNIQUE (kind, object_key),
    CONSTRAINT artifact_size_nonneg     CHECK (byte_size IS NULL OR byte_size >= 0),
    -- Готовый артефакт обязан иметь размер и контрольную сумму входов (Р-10).
    CONSTRAINT artifact_ready_has_size  CHECK (state <> 'READY' OR byte_size IS NOT NULL)
);

COMMENT ON TABLE artifact IS
    'Реестр результатов заданий. Пока состояние не READY, артефакт не считается готовым (FR-091).';

CREATE INDEX artifact_job_idx     ON artifact (job_id) WHERE job_id IS NOT NULL;
CREATE INDEX artifact_state_idx   ON artifact (kind, state);
CREATE INDEX artifact_placement_idx ON artifact (placement) WHERE placement = 'SSD';
