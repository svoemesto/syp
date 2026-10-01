-- 07_settings.sql — SYP, PostgreSQL 16
--
-- Настройки анализа сериала: пороги, подбираемые замером, и параметры
-- кластеризации. Значения по умолчанию — стартовые, рабочие значения
-- подбираются замером на тестовой серии (ADR-0003, research.md М-03, М-04, М-08).
-- Здесь же — параметры выдачи рецепта: версия формата сценария и правило
-- аудиодорожек при склейке без перекодирования (ADR-0009, Р-01/Р-14).
--
-- Спецификация: FR-040, FR-043, FR-065, FR-073, FR-083.
-- constitution: пороги не зашиваются в код.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.22.
-- Миграция добавочная, применяется один раз после 06_recipe.sql.
--
-- Происхождение: план 004-speckit-plan, тот же номер файла. Отличие от прежней
-- редакции — добавлены два ключа рецепта и строки для них в триггере по
-- умолчанию. Применённых миграций на момент 2026-10-02 нет, база не
-- разворачивалась.

CREATE TABLE analysis_setting (
    id          BIGSERIAL PRIMARY KEY,
    serial_id   BIGINT      NOT NULL
        REFERENCES serial (id) ON DELETE CASCADE,
    key         TEXT        NOT NULL,
    value       JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT analysis_setting_key_not_blank CHECK (btrim(key) <> ''),
    CONSTRAINT analysis_setting_key_unique    UNIQUE (serial_id, key)
);

COMMENT ON TABLE analysis_setting IS
    'Настройки анализа сериала и выдачи рецепта. Хранятся здесь, а не в коде:
     пороги подбираются замером и меняются без пересборки.';

CREATE INDEX analysis_setting_serial_idx ON analysis_setting (serial_id);

INSERT INTO analysis_setting (serial_id, key, value)
SELECT s.id, v.key, v.value
FROM serial s
CROSS JOIN (VALUES
    -- Порог границы сцены: оценка кадра выше порога означает смену сцены.
    ('scene.threshold',            '8'::jsonb),
    -- Порог границы плана: ниже порога сцены, внутри сцены.
    ('shot.threshold',             '4'::jsonb),
    -- Пороги размера плана: границы долей площади рамки лица в площади кадра,
    -- от большей кругности к меньшей. Значения подлежат подбору замером (М-04).
    ('shot.size.thresholds',       '[0.35, 0.22, 0.13, 0.075, 0.04, 0.02, 0.009, 0.003]'::jsonb),
    -- Рамка, вытянутая сильнее этой пропорции, лицом не считается (Т-18).
    ('face.not_person_aspect',     '4'::jsonb),
    -- Порог уверенности детектора: параметр задания, не константа кода (Т-02).
    ('face.detect_threshold',      '0.5'::jsonb),
    -- Параметры кластеризации лиц на холодном старте (Т-16).
    ('cluster.count',              '256'::jsonb),
    ('cluster.merge_threshold',    '0.65'::jsonb),
    -- Размер листа превью: 16 на 16 превью 135x75, 256 кадров в листе (Т-03).
    ('preview.sheet.cols',         '16'::jsonb),
    ('preview.sheet.rows',         '16'::jsonb),
    -- Версия формата сценария сборки. Попадает в сценарий и в подпись, поэтому
    -- старый воркер отвергает сценарий новой версии, а не разбирает его
    -- наугад (FR-090, FR-089c).
    ('recipe.schema_version',      '1'::jsonb),
    -- Число аудиодорожек в готовом файле: одна (Р-14, следствие Р-01).
    ('recipe.audio_track_count',   '1'::jsonb)
) AS v(key, value);

-- Настройки появляются при создании сериала, а не только на момент миграции:
-- сериал может быть заведён позже, и у него тоже должны быть значения по
-- умолчанию. Триггер ставится после того, как заполнение для уже
-- существующих сериалов выполнено.
CREATE OR REPLACE FUNCTION syp_apply_default_settings()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO analysis_setting (serial_id, key, value) VALUES
        (NEW.id, 'scene.threshold',         '8'::jsonb),
        (NEW.id, 'shot.threshold',          '4'::jsonb),
        (NEW.id, 'shot.size.thresholds',    '[0.35, 0.22, 0.13, 0.075, 0.04, 0.02, 0.009, 0.003]'::jsonb),
        (NEW.id, 'face.not_person_aspect',  '4'::jsonb),
        (NEW.id, 'face.detect_threshold',   '0.5'::jsonb),
        (NEW.id, 'cluster.count',           '256'::jsonb),
        (NEW.id, 'cluster.merge_threshold', '0.65'::jsonb),
        (NEW.id, 'preview.sheet.cols',      '16'::jsonb),
        (NEW.id, 'preview.sheet.rows',      '16'::jsonb),
        (NEW.id, 'recipe.schema_version',   '1'::jsonb),
        (NEW.id, 'recipe.audio_track_count','1'::jsonb);
    RETURN NEW;
END;
$$;

CREATE TRIGGER serial_apply_default_settings
    AFTER INSERT ON serial
    FOR EACH ROW EXECUTE FUNCTION syp_apply_default_settings();

COMMENT ON FUNCTION syp_apply_default_settings() IS
    'Каждый новый сериал получает значения настроек по умолчанию; дальше оператор их меняет.';
