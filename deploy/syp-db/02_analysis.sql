-- 02_analysis.sql — SYP, PostgreSQL 16
--
-- Структура видео: прогоны анализа, сырые границы, значимые кадры,
-- рабочие сцены и планы.
-- Спецификация: FR-010…FR-016, FR-020…FR-024, FR-090, FR-093.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.4–2.8.
-- Происхождение: план 004-speckit-plan, тот же номер файла, без изменений:
-- схема «рецепт» (ADR-0009) этих таблиц не касается. Применённых миграций
-- на момент 2026-10-02 нет, база не разворачивалась.
-- Миграция добавочная, применяется один раз после 01_catalog.sql.

CREATE TABLE analysis_run (
    id                 BIGSERIAL    PRIMARY KEY,
    series_id          BIGINT       NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    kind               TEXT         NOT NULL,
    algorithm_version  TEXT         NOT NULL,
    params_hash        TEXT         NOT NULL,
    state              TEXT         NOT NULL,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    error_text         TEXT,
    is_stale           BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT analysis_run_kind_known     CHECK (kind IN ('STRUCTURE', 'FACES')),
    CONSTRAINT analysis_run_state_known    CHECK (state IN ('CREATING', 'WORKING', 'DONE', 'ERROR')),
    CONSTRAINT analysis_run_version_not_blank CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT analysis_run_params_not_blank  CHECK (btrim(params_hash) <> ''),
    CONSTRAINT analysis_run_error_only_on_error CHECK (
        (state = 'ERROR') OR (error_text IS NULL)
    ),
    CONSTRAINT analysis_run_time_order CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    )
);

COMMENT ON TABLE analysis_run IS
    'Прогон анализа: результат зависит от версии алгоритма и параметров (FR-090).';

CREATE INDEX analysis_run_series_kind_idx ON analysis_run (series_id, kind, id DESC);

CREATE TABLE raw_boundary (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT    NOT NULL
        REFERENCES analysis_run (id) ON DELETE CASCADE,
    level        TEXT      NOT NULL,
    first_frame  INTEGER   NOT NULL,
    last_frame   INTEGER   NOT NULL,

    CONSTRAINT raw_boundary_level_known CHECK (level IN ('SCENE', 'SHOT')),
    CONSTRAINT raw_boundary_range       CHECK (first_frame >= 0 AND last_frame >= first_frame)
);

COMMENT ON TABLE raw_boundary IS
    'Сырой результат автоматики. Хранится отдельно от рабочих границ и не перезаписывается ручными правками (FR-093).';

CREATE INDEX raw_boundary_run_level_idx ON raw_boundary (run_id, level, first_frame);

CREATE TABLE frame (
    id                 BIGSERIAL PRIMARY KEY,
    series_id          BIGINT    NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    frame_number       INTEGER   NOT NULL,
    is_scene_boundary  BOOLEAN   NOT NULL DEFAULT FALSE,
    is_shot_boundary   BOOLEAN   NOT NULL DEFAULT FALSE,
    face_count         INTEGER   NOT NULL DEFAULT 0,
    size_hint          TEXT,

    CONSTRAINT frame_number_nonneg    CHECK (frame_number >= 0),
    CONSTRAINT frame_number_unique     UNIQUE (series_id, frame_number),
    CONSTRAINT frame_face_count_nonneg CHECK (face_count >= 0)
);

COMMENT ON TABLE frame IS
    'Только значимые кадры: границы сцен, границы планов, кадры с лицами. Кадр не хранится как изображение (FR-024).';

CREATE TABLE scene (
    id           BIGSERIAL PRIMARY KEY,
    series_id    BIGINT    NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    first_frame  INTEGER   NOT NULL,
    last_frame   INTEGER   NOT NULL,
    location_id  BIGINT
        REFERENCES location (id) ON DELETE SET NULL,
    origin       TEXT      NOT NULL DEFAULT 'AUTO',
    run_id       BIGINT
        REFERENCES analysis_run (id) ON DELETE SET NULL,
    is_stale     BOOLEAN   NOT NULL DEFAULT FALSE,

    CONSTRAINT scene_range         CHECK (first_frame >= 0 AND last_frame >= first_frame),
    CONSTRAINT scene_origin_known  CHECK (origin IN ('AUTO', 'OPERATOR', 'CANCELLED'))
);

COMMENT ON TABLE scene IS
    'Сцена — непрерывный участок серии. Связь сцена ↔ план вычисляется по диапазонам кадров (ADR-0007).';
COMMENT ON COLUMN scene.origin IS
    'Происхождение границы: AUTO — алгоритм, OPERATOR — оператор, CANCELLED — решение алгоритма отменено (FR-015, FR-016).';
COMMENT ON COLUMN scene.location_id IS
    'Место действия назначается только вручную, автоматического определения нет (FR-052).';

CREATE INDEX scene_series_first_idx  ON scene (series_id, first_frame);
CREATE INDEX scene_series_last_idx   ON scene (series_id, last_frame);
CREATE INDEX scene_location_idx      ON scene (location_id) WHERE location_id IS NOT NULL;

CREATE TABLE shot (
    id           BIGSERIAL PRIMARY KEY,
    series_id    BIGINT    NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    first_frame  INTEGER   NOT NULL,
    last_frame   INTEGER   NOT NULL,
    size         TEXT      NOT NULL DEFAULT 'NONE',
    size_origin  TEXT      NOT NULL DEFAULT 'AUTO',
    origin       TEXT      NOT NULL DEFAULT 'AUTO',
    run_id       BIGINT
        REFERENCES analysis_run (id) ON DELETE SET NULL,
    is_stale     BOOLEAN   NOT NULL DEFAULT FALSE,

    CONSTRAINT shot_range        CHECK (first_frame >= 0 AND last_frame >= first_frame),
    CONSTRAINT shot_size_known   CHECK (size IN ('NONE', 'ECU', 'BCU', 'CU', 'MCU', 'MS', 'MLS', 'LS', 'VLS', 'XLS')),
    CONSTRAINT shot_size_origin  CHECK (size_origin IN ('AUTO', 'OPERATOR')),
    CONSTRAINT shot_origin       CHECK (origin IN ('AUTO', 'OPERATOR', 'CANCELLED'))
);

COMMENT ON TABLE shot IS
    'План — непрерывный участок внутри сцены. Размер вычисляется по самому крупному лицу плана (ADR-0003).';
COMMENT ON COLUMN shot.size_origin IS
    'AUTO — вычислено автоматически, OPERATOR — исправлено оператором (FR-043).';

CREATE INDEX shot_series_first_idx ON shot (series_id, first_frame);
CREATE INDEX shot_series_last_idx  ON shot (series_id, last_frame);
CREATE INDEX shot_size_idx         ON shot (size) WHERE size <> 'NONE';
CREATE INDEX shot_location_join_idx ON shot (series_id, first_frame, last_frame)
    INCLUDE (size, size_origin, origin);
