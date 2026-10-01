-- 04_selection.sql — SYP, PostgreSQL 16
--
-- Сохраняемые фильтры: именованное дерево условий до трёх уровней.
-- Спецификация: FR-070…FR-073.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.14–2.16.
-- Происхождение: план 004-speckit-plan, тот же номер файла, без изменений:
-- схема «рецепт» (ADR-0009) этих таблиц не касается. Применённых миграций
-- на момент 2026-10-02 нет, база не разворачивалась.
-- Миграция добавочная, применяется один раз после 03_characters.sql.

CREATE TABLE syp_filter (
    id          BIGSERIAL   PRIMARY KEY,
    serial_id   BIGINT      NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT syp_filter_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT syp_filter_name_unique    UNIQUE (serial_id, name)
);

COMMENT ON TABLE syp_filter IS
    'Сохранённый именованный фильтр: сохраняется, применяется повторно, правится и удаляется (FR-072).';

CREATE TABLE filter_group (
    id               BIGSERIAL PRIMARY KEY,
    filter_id        BIGINT    NOT NULL
        REFERENCES syp_filter (id) ON DELETE CASCADE,
    parent_group_id  BIGINT
        REFERENCES filter_group (id) ON DELETE CASCADE,
    ordinal          INTEGER   NOT NULL,
    conjunction      TEXT      NOT NULL,

    CONSTRAINT filter_group_ordinal_nonneg CHECK (ordinal >= 0),
    CONSTRAINT filter_group_conjunction_known CHECK (conjunction IN ('AND', 'OR')),
    CONSTRAINT filter_group_not_self_parent CHECK (parent_group_id IS DISTINCT FROM id)
);

COMMENT ON TABLE filter_group IS
    'Узел дерева условий. Глубина вложенности не превышает трёх уровней; проверяется при сохранении фильтра (FR-070).';

CREATE INDEX filter_group_filter_idx  ON filter_group (filter_id, ordinal);
CREATE INDEX filter_group_parent_idx  ON filter_group (parent_group_id) WHERE parent_group_id IS NOT NULL;

CREATE TABLE filter_condition (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT    NOT NULL
        REFERENCES filter_group (id) ON DELETE CASCADE,
    ordinal        INTEGER   NOT NULL,
    condition_type TEXT      NOT NULL,
    subject        TEXT      NOT NULL,
    operator       TEXT      NOT NULL,
    value          JSONB     NOT NULL,
    negated        BOOLEAN   NOT NULL DEFAULT FALSE,

    CONSTRAINT filter_condition_ordinal_nonneg CHECK (ordinal >= 0),
    -- Белый список типов условий первого среза (FR-073). Тип вне списка
    -- отвергается при сохранении: это даёт явную ошибку, а не пустой результат
    -- (ADR-0008, последствие 2).
    CONSTRAINT filter_condition_type_known CHECK (
        condition_type IN ('PERSON', 'LOCATION', 'SHOT_TYPE', 'SHOT_SIZE', 'FRAME_RANGE', 'HAS_FACE')
    ),
    CONSTRAINT filter_condition_subject_known   CHECK (subject IN ('SCENE', 'SHOT')),
    CONSTRAINT filter_condition_operator_known  CHECK (
        operator IN ('IN', 'NOT_IN', 'BETWEEN', 'IS_NULL', 'IS_NOT_NULL')
    ),
    -- Условие без операнда допускается только для операторов проверки наличия.
    CONSTRAINT filter_condition_value_needed CHECK (
        operator IN ('IS_NULL', 'IS_NOT_NULL') OR jsonb_typeof(value) <> 'null'
    )
);

COMMENT ON TABLE filter_condition IS
    'Предикат внутри группы фильтра. Исполняется одним универсальным механизмом через реестр обработчиков (ADR-0008).';

CREATE INDEX filter_condition_group_idx ON filter_condition (group_id, ordinal);
CREATE INDEX filter_condition_type_idx   ON filter_condition (condition_type);
