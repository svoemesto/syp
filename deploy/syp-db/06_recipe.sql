-- 06_recipe.sql — SYP, PostgreSQL 16
--
-- Рецепт: справочник сумм исходников и сценарии сборки.
-- Спецификация: FR-080…FR-089e.
-- ADR-0009: сервер формирует сценарий сборки, а не видеофайл; проверка
-- целостности идёт по sha256 исходников, а подлинность сценария — по подписи.
-- Модель: specs/001-first-vertical-slice/data-model.md, разделы 2.19–2.21.
-- Миграция добавочная, применяется один раз после 05_jobs.sql.
--
-- Происхождение: план 004-speckit-plan, тот же номер файла. Прежний файл
-- `06_assembly.sql` (сборка подборки на сервере) заменён: по ADR-0009 видео на
-- сервере не собирается. Применённых миграций на 2026-10-02 нет.

-- ---------------------------------------------------------------------------
-- 1. Справочник сумм исходников.
--
-- Считается один раз, заданием HASH, при регистрации эпизода. Это эталон,
-- с которым воркер на машине пользователя сверяет файл ДО нарезки (FR-089,
-- SC-012). Хранится история пересчётов, поэтому пересчёт не затирает прежнюю
-- запись, а активной остаётся ровно одна.
-- ---------------------------------------------------------------------------

CREATE TABLE source_file_checksum (
    id           BIGSERIAL    PRIMARY KEY,
    series_id    BIGINT       NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    algorithm    TEXT         NOT NULL DEFAULT 'SHA-256',
    digest       TEXT         NOT NULL,
    byte_size    BIGINT       NOT NULL,
    file_mtime   TIMESTAMPTZ  NOT NULL,
    state        TEXT         NOT NULL DEFAULT 'CREATING',
    is_stale     BOOLEAN      NOT NULL DEFAULT FALSE,
    computed_at  TIMESTAMPTZ,
    error_text   TEXT,

    CONSTRAINT source_file_checksum_algorithm_known CHECK (algorithm = 'SHA-256'),
    -- 64 шестнадцатеричных символа в нижнем регистре: формат, который
    -- понимает sha256sum на машине пользователя без преобразований.
    CONSTRAINT source_file_checksum_digest_hex  CHECK (digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT source_file_checksum_size_pos    CHECK (byte_size > 0),
    CONSTRAINT source_file_checksum_state_known CHECK (state IN ('CREATING', 'WORKING', 'DONE', 'ERROR')),
    -- Готовая сумма обязана быть посчитана; ошибка обязана нести текст.
    CONSTRAINT source_file_checksum_done_when_stamped CHECK (
        state <> 'DONE' OR computed_at IS NOT NULL
    ),
    CONSTRAINT source_file_checksum_error_only_on_error CHECK (
        state <> 'ERROR' OR error_text IS NOT NULL
    ),
    CONSTRAINT source_file_checksum_no_error_when_done CHECK (
        state <> 'DONE' OR error_text IS NULL
    )
);

COMMENT ON TABLE source_file_checksum IS
    'Справочник сумм sha256 исходных файлов эпизодов. Эталон проверки целостности
     на машине пользователя: воркер сверяет файл ДО нарезки и отказывается
     работать при несовпадении, называя файл (FR-089, SC-012).';
COMMENT ON COLUMN source_file_checksum.is_stale IS
    'Истина, если файл изменился после подсчёта: сценарий, ссылающийся на
     устаревшую сумму, выдавать нельзя (FR-090).';

-- Ровно одна актуальная сумма на эпизод: частичный уникальный индекс.
CREATE UNIQUE INDEX source_file_checksum_one_current_idx
    ON source_file_checksum (series_id) WHERE state = 'DONE' AND is_stale = FALSE;

CREATE INDEX source_file_checksum_series_idx
    ON source_file_checksum (series_id, id DESC);

-- ---------------------------------------------------------------------------
-- 2. Сценарий сборки.
--
-- Рецепт — исполняемое описание подборки: список фрагментов с относительными
-- путями и границами по номерам кадров. Видео здесь нет и не появится
-- (FR-088, FR-089e).
-- ---------------------------------------------------------------------------

CREATE TABLE build_recipe (
    id                    BIGSERIAL   PRIMARY KEY,
    serial_id             BIGINT      NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    name                  TEXT        NOT NULL,
    schema_version        INTEGER     NOT NULL,
    state                 TEXT        NOT NULL DEFAULT 'CREATING',
    artifact_id           BIGINT
        REFERENCES artifact (id) ON DELETE SET NULL,
    content_sha256        TEXT,
    signature             TEXT,
    signing_key_id        TEXT,
    item_count            INTEGER     NOT NULL DEFAULT 0,
    expected_duration_ms  BIGINT,
    expected_frame_count  BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ,
    error_text            TEXT,

    CONSTRAINT build_recipe_name_not_blank    CHECK (btrim(name) <> ''),
    CONSTRAINT build_recipe_schema_version_pos CHECK (schema_version > 0),
    CONSTRAINT build_recipe_state_known       CHECK (state IN ('CREATING', 'WORKING', 'DONE', 'ERROR')),
    CONSTRAINT build_recipe_item_count_nonneg CHECK (item_count >= 0),
    CONSTRAINT build_recipe_expected_nonneg   CHECK (
        (expected_duration_ms IS NULL OR expected_duration_ms >= 0)
        AND (expected_frame_count IS NULL OR expected_frame_count >= 0)
    ),
    -- Выданный сценарий обязан быть подписан и иметь однозначное содержимое:
    -- без этого подпись нечего проверять (FR-089c).
    CONSTRAINT build_recipe_done_signed        CHECK (
        state <> 'DONE'
        OR (artifact_id IS NOT NULL
            AND content_sha256 IS NOT NULL
            AND signature IS NOT NULL
            AND signing_key_id IS NOT NULL
            AND item_count > 0
            AND expected_duration_ms IS NOT NULL
            AND expected_frame_count IS NOT NULL
            AND finished_at IS NOT NULL)
    ),
    CONSTRAINT build_recipe_content_sha256_hex CHECK (
        content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT build_recipe_signature_base64   CHECK (
        signature IS NULL OR signature ~ '^[A-Za-z0-9+/]+={0,2}$'
    ),
    CONSTRAINT build_recipe_error_only_on_error CHECK (state <> 'ERROR' OR error_text IS NOT NULL),
    CONSTRAINT build_recipe_signature_together  CHECK (
        (signature IS NULL AND signing_key_id IS NULL AND content_sha256 IS NULL)
        OR (signature IS NOT NULL AND signing_key_id IS NOT NULL AND content_sha256 IS NOT NULL)
    ),
    CONSTRAINT build_recipe_time_order CHECK (
        finished_at IS NULL OR finished_at >= created_at
    )
);

COMMENT ON TABLE build_recipe IS
    'Сценарий сборки — рецепт для машины пользователя: фрагменты с
     относительными путями и границами по кадрам. Видеофайл не хранится и не
     передаётся (FR-080, FR-088, ADR-0009).';
COMMENT ON COLUMN build_recipe.artifact_id IS
    'Канонические байты сценария в объектном хранилище: именно они
     подписываются, и именно их получает пользователь.';
COMMENT ON COLUMN build_recipe.signature IS
    'Подпись алгоритмом Ed25519 от канонических байтов, в base64. Воркер
     проверяет её ДО выполнения сценария (FR-089c).';
COMMENT ON COLUMN build_recipe.schema_version IS
    'Версия формата сценария. Смена версии помечает ранее выданные сценарии
     устаревшими (FR-090).';
COMMENT ON COLUMN build_recipe.expected_duration_ms IS
    'Расчётная длительность подборки по сумме кадров. Фактическая измеряется
     на машине пользователя и фиксируется в отчёте о выполнении (FR-083).';

CREATE INDEX build_recipe_serial_idx   ON build_recipe (serial_id, created_at DESC);
CREATE INDEX build_recipe_state_idx    ON build_recipe (state, created_at DESC);
CREATE INDEX build_recipe_digest_idx   ON build_recipe (content_sha256) WHERE content_sha256 IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Фрагмент сценария.
--
-- Одна строка — один фрагмент подборки в порядке следования. Снимок
-- метаданных сцены (место действия, персонажи) нужен для локального показа
-- со списком сцен: сайт в показе не участвует (FR-089d), поэтому сценарий
-- обязан быть самодостаточным.
-- ---------------------------------------------------------------------------

CREATE TABLE build_recipe_item (
    recipe_id       BIGINT     NOT NULL
        REFERENCES build_recipe (id) ON DELETE CASCADE,
    ordinal         INTEGER    NOT NULL,
    scene_id        BIGINT     NOT NULL
        REFERENCES scene (id) ON DELETE CASCADE,
    series_id       BIGINT     NOT NULL
        REFERENCES series (id) ON DELETE CASCADE,
    series_name     TEXT       NOT NULL,
    relative_path   TEXT       NOT NULL,
    source_sha256   TEXT       NOT NULL,
    first_frame     INTEGER    NOT NULL,
    last_frame      INTEGER    NOT NULL,
    cut_first_frame INTEGER    NOT NULL,
    cut_last_frame  INTEGER    NOT NULL,
    scene_title     TEXT,
    location_name   TEXT,
    person_names    JSONB      NOT NULL DEFAULT '[]'::jsonb,

    PRIMARY KEY (recipe_id, ordinal),
    CONSTRAINT build_recipe_item_ordinal_nonneg  CHECK (ordinal >= 0),
    CONSTRAINT build_recipe_item_series_not_blank CHECK (btrim(series_name) <> ''),
    CONSTRAINT build_recipe_item_path_not_blank  CHECK (btrim(relative_path) <> ''),
    -- Путь относителен и не выходит за пределы своего дерева: иначе сценарий
    -- отличал бы файл эпизода от файла за его пределами (FR-089a).
    CONSTRAINT build_recipe_item_path_relative   CHECK (
        relative_path NOT LIKE '/%' AND relative_path NOT LIKE '../%'
        AND relative_path NOT LIKE '%/../%' AND relative_path NOT LIKE '%/..'
    ),
    CONSTRAINT build_recipe_item_digest_hex      CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    -- Номер кадра — единственный источник правды (ADR-0001).
    CONSTRAINT build_recipe_item_range           CHECK (
        first_frame >= 0 AND last_frame >= first_frame
    ),
    -- Фактические границы после округления до ключевого кадра: начало фрагмента
    -- не позже расчётного, конец — не раньше (ADR-0006, FR-082).
    CONSTRAINT build_recipe_item_cut_range       CHECK (
        cut_first_frame >= 0
        AND cut_last_frame >= cut_first_frame
        AND cut_first_frame <= first_frame
        AND cut_last_frame >= last_frame
    ),
    CONSTRAINT build_recipe_item_persons_array   CHECK (jsonb_typeof(person_names) = 'array')
);

COMMENT ON TABLE build_recipe_item IS
    'Фрагмент сценария: один фрагмент подборки. Путь относительный, границы —
     по номерам кадров, обе пары границ сохраняются (FR-080, FR-082).';
COMMENT ON COLUMN build_recipe_item.relative_path IS
    'Путь к файлу эпизода относительно корня фильма; структура каталогов
     повторяет машину администратора (FR-089a).';
COMMENT ON COLUMN build_recipe_item.source_sha256 IS
    'Снимок эталонной суммы на момент выдачи сценария. Воркер сверяет файл по
     этой сумме ДО нарезки (FR-089).';
COMMENT ON COLUMN build_recipe_item.cut_first_frame IS
    'Ближайший ключевой кадр не позже начала плана. Нарезка без перекодирования
     требует, чтобы фрагмент начинался с самостоятельного ключевого кадра
     (ADR-0006).';
COMMENT ON COLUMN build_recipe_item.person_names IS
    'Снимок имён персонажей сцены на момент выдачи — для локального показа
     со списком сцен (FR-089d).';
CREATE INDEX build_recipe_item_recipe_idx  ON build_recipe_item (recipe_id, ordinal);
CREATE INDEX build_recipe_item_series_idx  ON build_recipe_item (series_id);
CREATE INDEX build_recipe_item_scene_idx   ON build_recipe_item (scene_id);
