-- 03_characters.sql — SYP, PostgreSQL 16
--
-- Лица, персоны, эмбеддинги, версии модели распознавания.
-- Спецификация: FR-030…FR-036, FR-060…FR-065.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.9–2.13.
-- Происхождение: план 004-speckit-plan, тот же номер файла, без изменений:
-- схема «рецепт» (ADR-0009) этих таблиц не касается. Применённых миграций
-- на момент 2026-10-02 нет, база не разворачивалась.
-- Миграция добавочная, применяется один раз после 02_analysis.sql.
--
-- Порядок создания таблиц: внешние ключи идут только на уже созданные
-- таблицы, поэтому version model создаётся раньше face.

CREATE TABLE person (
    id                BIGSERIAL PRIMARY KEY,
    serial_id         BIGINT    NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    name              TEXT      NOT NULL,
    recognizer_key    TEXT,
    kind              TEXT      NOT NULL,

    CONSTRAINT person_name_not_blank     CHECK (btrim(name) <> ''),
    CONSTRAINT person_name_unique        UNIQUE (serial_id, name),
    CONSTRAINT person_key_unique         UNIQUE (serial_id, recognizer_key),
    CONSTRAINT person_kind_known         CHECK (kind IN ('PERSON', 'UNRECOGNIZED', 'NONPERSON')),
    CONSTRAINT person_key_required       CHECK (
        (kind = 'PERSON' AND recognizer_key IS NOT NULL AND btrim(recognizer_key) <> '')
        OR (kind <> 'PERSON' AND recognizer_key IS NULL)
    )
);

COMMENT ON TABLE person IS
    'Персона — именованная личность. Служебные виды UNRECOGNIZED и NONPERSON — заглушки, а не ошибка (Р-12).';
COMMENT ON COLUMN person.recognizer_key IS
    'Ключ класса в модели. Не зависит от отображаемого имени: переименование не ломает модель.';

CREATE TABLE model_version (
    id                 BIGSERIAL          PRIMARY KEY,
    serial_id          BIGINT             NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    created_at         TIMESTAMPTZ        NOT NULL DEFAULT now(),
    algorithm_version  TEXT               NOT NULL,
    params_hash        TEXT               NOT NULL,
    example_count      INTEGER            NOT NULL,
    classes            TEXT               NOT NULL,
    threshold          DOUBLE PRECISION   NOT NULL,
    artifact_key       TEXT,
    is_active          BOOLEAN            NOT NULL DEFAULT FALSE,
    state              TEXT               NOT NULL DEFAULT 'CREATING',

    CONSTRAINT model_version_algorithm_not_blank CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT model_version_params_not_blank     CHECK (btrim(params_hash) <> ''),
    CONSTRAINT model_version_classes_not_blank   CHECK (btrim(classes) <> ''),
    CONSTRAINT model_version_example_count_nonneg CHECK (example_count >= 0),
    CONSTRAINT model_version_threshold_range      CHECK (threshold > 0 AND threshold <= 1),
    CONSTRAINT model_version_state_known          CHECK (state IN ('CREATING', 'WORKING', 'DONE', 'ERROR')),
    CONSTRAINT model_version_artifact_when_done   CHECK (state <> 'DONE' OR artifact_key IS NOT NULL)
);

COMMENT ON TABLE model_version IS
    'Версия модели распознавания: результат одного обучения. Предыдущие версии сохраняются (ADR-0004, FR-062).';
COMMENT ON COLUMN model_version.threshold IS
    'Порог распознавания — настройка обучения, а не константа кода (FR-065).';

-- Ровно одна активная версия на фильм: частичный уникальный индекс.
CREATE UNIQUE INDEX model_version_one_active_idx ON model_version (serial_id) WHERE is_active;

CREATE INDEX model_version_serial_idx ON model_version (serial_id, created_at DESC);

CREATE TABLE face (
    id                               BIGSERIAL        PRIMARY KEY,
    series_id                        BIGINT           NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    frame_number                     INTEGER          NOT NULL,
    face_index                       INTEGER          NOT NULL,
    x1                               INTEGER          NOT NULL,
    y1                               INTEGER          NOT NULL,
    x2                               INTEGER          NOT NULL,
    y2                               INTEGER          NOT NULL,
    shot_id                          BIGINT
        REFERENCES shot (id) ON DELETE SET NULL,
    person_id                        BIGINT           NOT NULL
        REFERENCES person (id) ON DELETE RESTRICT,
    origin                           TEXT             NOT NULL DEFAULT 'AUTO',
    is_example                       BOOLEAN          NOT NULL DEFAULT FALSE,
    recognized_name                  TEXT,
    recognized_by_model_version_id   BIGINT
        REFERENCES model_version (id) ON DELETE RESTRICT,
    recognize_probability            DOUBLE PRECISION,
    detect_confidence                DOUBLE PRECISION,

    CONSTRAINT face_frame_number_nonneg  CHECK (frame_number >= 0),
    CONSTRAINT face_index_nonneg         CHECK (face_index >= 0),
    CONSTRAINT face_natural_key_unique   UNIQUE (series_id, frame_number, face_index),
    CONSTRAINT face_box_order            CHECK (x1 < x2 AND y1 < y2),
    CONSTRAINT face_origin_known         CHECK (origin IN ('AUTO', 'OPERATOR')),
    CONSTRAINT face_probability_range    CHECK (
        recognize_probability IS NULL OR (recognize_probability >= 0 AND recognize_probability <= 1)
    ),
    CONSTRAINT face_confidence_range     CHECK (
        detect_confidence IS NULL OR (detect_confidence >= 0 AND detect_confidence <= 1)
    ),
    CONSTRAINT face_recognition_pair     CHECK (
        (recognized_by_model_version_id IS NULL AND recognized_name IS NULL)
        OR recognized_by_model_version_id IS NOT NULL
    )
);

COMMENT ON TABLE face IS
    'Лицо — рамка в кадре. Ищется в каждом кадре эпизода (ADR-0002); кадры не копятся.';
COMMENT ON COLUMN face.shot_id IS
    'Принадлежность к плану (FR-034). Всегда соответствует правилу диапазонов и пересчитывается в транзакции доводки границ.';
COMMENT ON COLUMN face.origin IS
    'OPERATOR — лицо нарисовано мышью оператором; повторным анализом не удаляется (FR-032, SC-006).';
COMMENT ON COLUMN face.recognized_by_model_version_id IS
    'Версия модели, которой лицо опознано (FR-063). Пусто — лицо не опознано.';

CREATE INDEX face_shot_idx    ON face (shot_id) WHERE shot_id IS NOT NULL;
CREATE INDEX face_frame_idx   ON face (series_id, frame_number);
CREATE INDEX face_person_idx  ON face (person_id);
CREATE INDEX face_example_idx ON face (series_id) WHERE is_example;
CREATE INDEX face_model_idx   ON face (recognized_by_model_version_id)
    WHERE recognized_by_model_version_id IS NOT NULL;

CREATE TABLE face_embedding (
    face_id             BIGINT PRIMARY KEY
        REFERENCES face (id) ON DELETE CASCADE,
    embedding_model_key TEXT   NOT NULL,
    vector              REAL[] NOT NULL,

    CONSTRAINT face_embedding_model_not_blank CHECK (btrim(embedding_model_key) <> ''),
    CONSTRAINT face_embedding_vector_not_empty CHECK (cardinality(vector) > 0)
);

COMMENT ON TABLE face_embedding IS
    'Вектор признаков лица. Векторы разных моделей эмбеддингов не смешиваются (Р-09).';

CREATE INDEX face_embedding_model_idx ON face_embedding (embedding_model_key);

CREATE TABLE model_version_example (
    model_version_id  BIGINT NOT NULL
        REFERENCES model_version (id) ON DELETE CASCADE,
    face_id           BIGINT NOT NULL
        REFERENCES face (id) ON DELETE CASCADE,

    PRIMARY KEY (model_version_id, face_id)
);

COMMENT ON TABLE model_version_example IS
    'Состав обучающей выборки версии. Нужен для отката: переобучение на той же выборке восстанавливает прежние метки (SC-009).';

CREATE INDEX model_version_example_face_idx ON model_version_example (face_id);
