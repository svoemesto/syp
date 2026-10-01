-- 01_catalog.sql — SYP, PostgreSQL 16
--
-- Каталог: сериалы, серии, справочник мест действия.
-- Спецификация: specs/001-first-vertical-slice/spec.md (FR-001, FR-002, FR-050,
-- FR-089a).
-- Модель: specs/001-first-vertical-slice/data-model.md, разделы 2.1–2.3.
--
-- Миграция добавочная: применяется один раз, порядковый номер закреплён.
-- Правка применённой миграции запрещена; изменение — новым номером.
-- Автогенерация схемы запрещена (constitution III).
--
-- Происхождение: план 004-speckit-plan, тот же номер файла. Отличие от
-- прежней редакции — поле `source_root` у сериала: без него нельзя выдать
-- относительный путь к файлу серии, требуемый FR-089a (структура каталогов
-- на машине пользователя повторяет машину администратора). Применённых
-- миграций на момент 2026-10-02 нет, база не разворачивалась.

CREATE TABLE serial (
    id          BIGSERIAL   PRIMARY KEY,
    name        TEXT        NOT NULL,
    -- Корень каталога сериала на машине администратора. От него отсчитываются
    -- относительные пути, которые попадают в сценарий сборки (FR-089a).
    source_root TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT serial_name_not_blank      CHECK (btrim(name) <> ''),
    CONSTRAINT serial_source_root_not_blank CHECK (btrim(source_root) <> ''),
    CONSTRAINT serial_source_root_absolute CHECK (source_root LIKE '/%'),
    CONSTRAINT serial_source_root_no_trailing_slash CHECK (source_root !~ '/$'),
    CONSTRAINT serial_name_unique         UNIQUE (name)
);

COMMENT ON TABLE serial IS 'Сериал — произведение, с которым работает оператор.';
COMMENT ON COLUMN serial.source_root IS
    'Корень каталога сериала на машине администратора. Сценарий сборки обращается
     к файлам по путям относительно этого корня: у пользователя та же структура
     каталогов, но под своим корнем (FR-089a).';

CREATE TABLE location (
    id         BIGSERIAL PRIMARY KEY,
    serial_id  BIGINT      NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    CONSTRAINT location_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT location_name_unique_per_serial UNIQUE (serial_id, name)
);

COMMENT ON TABLE location IS 'Справочник мест действия сериала; назначается сцене только вручную (FR-051, FR-052).';

CREATE TABLE series (
    id                     BIGSERIAL     PRIMARY KEY,
    serial_id              BIGINT        NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    ordinal                INTEGER       NOT NULL,
    name                   TEXT          NOT NULL,
    source_path            TEXT          NOT NULL,
    file_size              BIGINT        NOT NULL,
    file_mtime             TIMESTAMPTZ   NOT NULL,
    frame_count            INTEGER       NOT NULL,
    time_base_num          INTEGER       NOT NULL,
    time_base_den          INTEGER       NOT NULL,
    width                  INTEGER       NOT NULL,
    height                 INTEGER       NOT NULL,
    duration_num           BIGINT        NOT NULL,
    duration_den           BIGINT        NOT NULL,
    video_codec            TEXT          NOT NULL,
    video_profile          TEXT,
    pixel_format           TEXT          NOT NULL,
    audio_codec            TEXT,
    audio_channels         INTEGER,
    audio_sample_rate      INTEGER,
    keyframe_bitmap        BYTEA,
    preview_sheet_count    INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT series_ordinal_positive      CHECK (ordinal >= 0),
    CONSTRAINT series_ordinal_unique        UNIQUE (serial_id, ordinal),
    CONSTRAINT series_source_path_unique    UNIQUE (source_path),
    CONSTRAINT series_source_path_absolute  CHECK (source_path LIKE '/%'),
    CONSTRAINT series_name_not_blank        CHECK (btrim(name) <> ''),
    CONSTRAINT series_frame_count_positive  CHECK (frame_count > 0),
    CONSTRAINT series_time_base_num_pos     CHECK (time_base_num > 0),
    CONSTRAINT series_time_base_den_pos     CHECK (time_base_den > 0),
    CONSTRAINT series_width_positive        CHECK (width > 0),
    CONSTRAINT series_height_positive       CHECK (height > 0),
    CONSTRAINT series_duration_num_pos      CHECK (duration_num > 0),
    CONSTRAINT series_duration_den_pos      CHECK (duration_den > 0),
    CONSTRAINT series_pixel_format_not_blank CHECK (btrim(pixel_format) <> ''),
    CONSTRAINT series_video_codec_not_blank CHECK (btrim(video_codec) <> ''),
    CONSTRAINT series_audio_channels_nonneg CHECK (audio_channels IS NULL OR audio_channels >= 0),
    CONSTRAINT series_audio_rate_nonneg     CHECK (audio_sample_rate IS NULL OR audio_sample_rate >= 0),
    CONSTRAINT series_sheet_count_nonneg    CHECK (preview_sheet_count >= 0)
);

COMMENT ON TABLE series IS
    'Серия — видеофайл сериала. Номер кадра — единственный источник правды для границ (ADR-0001).';
COMMENT ON COLUMN series.source_path IS
    'Путь к исходному видеофайлу. Файл только читается и никогда не изменяется.';
COMMENT ON COLUMN series.keyframe_bitmap IS
    'Карта ключевых кадров: один бит на кадр, бит 1 — кадр ключевой. Нумерация кадров с нуля.';

CREATE INDEX series_serial_idx ON series (serial_id, ordinal);
