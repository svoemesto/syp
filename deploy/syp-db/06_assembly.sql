-- 06_assembly.sql — SYP, PostgreSQL 16
--
-- Сборка подборки: выбранные сцены склеиваются в один видеофайл.
-- Спецификация: FR-080…FR-088.
-- ADR-0006: сборка идёт без перекодирования, округление до ключевого кадра
-- на стыках — осознанное решение; расчётная и фактическая длительность
-- показываются обе.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.19–2.20.
-- Миграция добавочная, применяется один раз после 05_jobs.sql.

CREATE TABLE assembly (
    id                     BIGSERIAL    PRIMARY KEY,
    name                   TEXT         NOT NULL,
    state                  TEXT         NOT NULL DEFAULT 'CREATING',
    job_id                 BIGINT
        REFERENCES job (id) ON DELETE SET NULL,
    artifact_id            BIGINT
        REFERENCES artifact (id) ON DELETE SET NULL,
    expected_duration_ms   BIGINT,
    actual_duration_ms     BIGINT,
    expected_frame_count   BIGINT,
    actual_frame_count     BIGINT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    error_text             TEXT,

    CONSTRAINT assembly_name_not_blank  CHECK (btrim(name) <> ''),
    CONSTRAINT assembly_state_known     CHECK (state IN ('CREATING', 'WORKING', 'DONE', 'ERROR')),
    CONSTRAINT assembly_expected_nonneg CHECK (
        (expected_duration_ms IS NULL OR expected_duration_ms >= 0)
        AND (expected_frame_count IS NULL OR expected_frame_count >= 0)
    ),
    CONSTRAINT assembly_actual_nonneg CHECK (
        (actual_duration_ms IS NULL OR actual_duration_ms >= 0)
        AND (actual_frame_count IS NULL OR actual_frame_count >= 0)
    ),
    -- Готовая сборка обязана быть измеримой и указывать на артефакт (FR-083, FR-084).
    CONSTRAINT assembly_done_has_artifact CHECK (
        state <> 'DONE' OR (artifact_id IS NOT NULL AND actual_duration_ms IS NOT NULL)
    ),
    CONSTRAINT assembly_error_only_on_error CHECK (state <> 'ERROR' OR error_text IS NOT NULL)
);

COMMENT ON TABLE assembly IS
    'Подборка — видеофайл из выбранных сцен. Инициатор — пользователь публичной части (FR-085).';
COMMENT ON COLUMN assembly.expected_duration_ms IS
    'Расчётная длительность по сумме кадров планов.';
COMMENT ON COLUMN assembly.actual_duration_ms IS
    'Фактическая длительность, измеренная заново после сборки. Расхождение с расчётной — округление на стыках (FR-082, FR-083).';

CREATE INDEX assembly_state_idx ON assembly (state, created_at DESC);
CREATE INDEX assembly_name_idx  ON assembly (name);

CREATE TABLE assembly_item (
    assembly_id    BIGINT  NOT NULL
        REFERENCES assembly (id) ON DELETE CASCADE,
    ordinal        INTEGER NOT NULL,
    scene_id       BIGINT  NOT NULL
        REFERENCES scene (id) ON DELETE CASCADE,
    series_id      BIGINT  NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    expected_first INTEGER NOT NULL,
    expected_last  INTEGER NOT NULL,
    actual_first   INTEGER,
    actual_last    INTEGER,

    PRIMARY KEY (assembly_id, ordinal),
    CONSTRAINT assembly_item_ordinal_nonneg CHECK (ordinal >= 0),
    CONSTRAINT assembly_item_expected_range CHECK (
        expected_first >= 0 AND expected_last >= expected_first
    ),
    CONSTRAINT assembly_item_actual_range CHECK (
        (actual_first IS NULL AND actual_last IS NULL)
        OR (actual_first >= 0 AND actual_last >= actual_first)
    )
);

COMMENT ON TABLE assembly_item IS
    'Состав подборки с сохранением порядка. Подборка допускает сцены из разных серий сериала (FR-086).';
COMMENT ON COLUMN assembly_item.series_id IS
    'Денормализовано для проверки совместимости серий до начала работы (FR-087).';
COMMENT ON COLUMN assembly_item.actual_first IS
    'Фактическая граница фрагмента после округления до ключевого кадра; пусто, пока фрагмент не нарезан.';
