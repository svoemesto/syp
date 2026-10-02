-- 18_recipe_schema_version_2.sql — SYP, PostgreSQL 16
--
-- Версия формата сценария поднята до 2.
--
-- Зачем. Решением владельца 2026-10-03 сущности переименованы, и следом
-- язык описания: «сериал» стал «фильм», «серия» — «эпизод». В сценарий
-- ушли новые имена полей — `movieId`, `movieName`, `episodeId`,
-- `episodeName` — и новое значение раскладки `rootLayout: MOVIE_TREE`
-- вместо `SERIAL_TREE`. Подпись сценария считается по каноническим
-- байтам целиком, поэтому переименование поля меняет подпись: воркер,
-- читающий версию 1, разобрал бы сценарий версии 2 наугад.
--
-- Версия формата обязана отражать формат, поэтому настройка
-- `recipe.schema_version` у всех фильмов становится равной 2, а
-- функция триггера получает то же значение по умолчанию. Прежние
-- сценарии не удаляются и не отзываются: при выдаче нового сценария
-- `RecipeCatalog` помечает сценарии прежней версии устаревшими, и они
-- по-прежнему проверяемы своим ключом (FR-090, ADR-0014).
--
-- Миграция добавочная, применяется один раз после 17_face_box_within_series.sql.
-- Спецификация: FR-090. Решение: ADR-0016.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.20.

UPDATE tbl_analysis_settings
   SET value = '2'::jsonb
 WHERE key = 'recipe.schema_version'
   AND value <> '2'::jsonb;

-- Триггер заводит настройки новым фильмам, и его тело хранится текстом:
-- без пересоздания новый фильм получил бы прежнюю версию формата.
CREATE OR REPLACE FUNCTION syp_apply_default_settings()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tbl_analysis_settings (id_movie, key, value) VALUES
        (NEW.id, 'scene.threshold',         '8'::jsonb),
        (NEW.id, 'shot.threshold',          '4'::jsonb),
        (NEW.id, 'shot.size.thresholds',    '[0.35, 0.22, 0.13, 0.075, 0.04, 0.02, 0.009, 0.003]'::jsonb),
        (NEW.id, 'face.not_person_aspect',  '4'::jsonb),
        (NEW.id, 'face.detect_threshold',   '0.5'::jsonb),
        (NEW.id, 'cluster.count',           '256'::jsonb),
        (NEW.id, 'cluster.merge_threshold', '0.65'::jsonb),
        (NEW.id, 'preview.sheet.cols',      '16'::jsonb),
        (NEW.id, 'preview.sheet.rows',      '16'::jsonb),
        (NEW.id, 'recipe.schema_version',   '2'::jsonb),
        (NEW.id, 'recipe.audio_track_count','1'::jsonb);
    RETURN NEW;
END;
$$;
