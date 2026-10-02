-- 16_tbl_prefix_rename.sql — SYP, PostgreSQL 16
--
-- Решение владельца 2026-10-03: имена таблиц сущностей получают префикс
-- `tbl_` и форму во множественном числе. Ключевые сущности меняют и имя:
-- `serial` → `tbl_movies` (Movie), `series` → `tbl_episodes` (Episode).
-- Столбцы-ссылки следуют тому же правилу: `serial_id` → `id_movie`,
-- `series_id` → `id_episode`.
--
-- Карта переименования — `.scratch/syp/wayfinding/RENAME-MAP.md`; таблиц 23,
-- столбцов-ссылок 15 (восемь `serial_id` и семь `series_id`).
--
-- Миграция добавочная, применяется один раз после 15_scene_title.sql.
-- Применённые миграции 01–15 не переписываются (constitution III): данные
-- не пересоздаются, таблицы и столбцы переименовываются на месте, поэтому
-- ограничения, внешние ключи, индексы и комментарии остаются целыми, а
-- содержимое строк не меняется.
--
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.

-- --- Таблицы ----------------------------------------------------------------
-- Порядок не важен: внешние ключи хранят ссылку на объект, а не на имя, и
-- PostgreSQL обновляет её сама. Объявлено в алфавитном порядке прежних имён,
-- чтобы совпадать с картой построчно.

ALTER TABLE serial                RENAME TO tbl_movies;
ALTER TABLE series                RENAME TO tbl_episodes;
ALTER TABLE analysis_run          RENAME TO tbl_analysis_runs;
ALTER TABLE analysis_setting      RENAME TO tbl_analysis_settings;
ALTER TABLE artifact              RENAME TO tbl_artifacts;
ALTER TABLE build_recipe          RENAME TO tbl_build_recipes;
ALTER TABLE build_recipe_item     RENAME TO tbl_build_recipe_items;
ALTER TABLE face                  RENAME TO tbl_faces;
ALTER TABLE face_embedding        RENAME TO tbl_face_embeddings;
ALTER TABLE filter_condition      RENAME TO tbl_filter_conditions;
ALTER TABLE filter_group          RENAME TO tbl_filter_groups;
ALTER TABLE frame                 RENAME TO tbl_frames;
ALTER TABLE job                   RENAME TO tbl_jobs;
ALTER TABLE location              RENAME TO tbl_locations;
ALTER TABLE model_version         RENAME TO tbl_model_versions;
ALTER TABLE model_version_example RENAME TO tbl_model_version_examples;
ALTER TABLE person                RENAME TO tbl_persons;
ALTER TABLE raw_boundary          RENAME TO tbl_raw_boundaries;
ALTER TABLE scene                 RENAME TO tbl_scenes;
ALTER TABLE season                RENAME TO tbl_seasons;
ALTER TABLE shot                  RENAME TO tbl_shots;
ALTER TABLE source_file_checksum  RENAME TO tbl_source_file_checksums;
ALTER TABLE syp_filter            RENAME TO tbl_filters;

-- --- Столбцы-ссылки на Movie (было serial_id) -------------------------------

ALTER TABLE tbl_locations            RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_episodes             RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_persons              RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_model_versions       RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_filters              RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_build_recipes        RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_analysis_settings    RENAME COLUMN serial_id TO id_movie;
ALTER TABLE tbl_seasons              RENAME COLUMN serial_id TO id_movie;

-- --- Столбцы-ссылки на Episode (было series_id) ----------------------------

ALTER TABLE tbl_analysis_runs        RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_frames               RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_scenes               RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_shots                RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_faces                RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_source_file_checksums RENAME COLUMN series_id TO id_episode;
ALTER TABLE tbl_build_recipe_items   RENAME COLUMN series_id TO id_episode;

-- --- Столбец-снимок имени (было series_name) -------------------------------
-- Фрагмент сценария помнит название эпизода на момент выдачи: переименование
-- эпизода в админке не должно менять уже подписанный сценарий. Столбец назван
-- по сущности, поэтому переименовывается вместе с ней.
ALTER TABLE tbl_build_recipe_items RENAME COLUMN series_name TO episode_name;

-- --- Вид задания в очереди --------------------------------------------------
-- `job.subject_type` называет сущность, к которой задание относится, и вида
-- `SERIES` больше не существует: серия стала эпизодом. Ограничение в базе
-- проверяет само значение, поэтому без его правки любое задание к эпизоду
-- отклонялось бы, а старое значение продолжало бы проходить.
--
-- Строки, созданные до переименования, приводятся к новому значению тем же
-- оператором: база не разворачивалась, но правило не должно зависеть от
-- того, когда именно применили миграцию.
ALTER TABLE tbl_jobs DROP CONSTRAINT job_subject_type_known;

UPDATE tbl_jobs SET subject_type = 'EPISODE' WHERE subject_type = 'SERIES';

ALTER TABLE tbl_jobs ADD CONSTRAINT job_subject_type_known CHECK (
    subject_type IS NULL OR subject_type IN ('EPISODE', 'MODEL_VERSION')
);

-- --- Тела функций PL/pgSQL --------------------------------------------------
-- Тело функции хранится текстом и при переименовании таблиц **не**
-- переписывается: переименование правит только имя объекта, на который
-- функция навешена. Функции ниже обращаются к `serial`, `person` и
-- `analysis_setting` поимённо, поэтому без их пересоздания каждый новый
-- сериал падал бы с «relation does not exist». Сами функции и триггеры на
-- них не переименовываются: они не сущности предметной области, и их имена
-- в карте не перечислены.

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
        (NEW.id, 'recipe.schema_version',   '1'::jsonb),
        (NEW.id, 'recipe.audio_track_count','1'::jsonb);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION syp_create_service_persons()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tbl_persons (id_movie, name, recognizer_key, kind) VALUES
        (NEW.id, 'Распознано, имя не подтверждено', NULL, 'UNRECOGNIZED'),
        (NEW.id, 'Не лицо',                          NULL, 'NONPERSON')
    ON CONFLICT (id_movie, kind) WHERE kind <> 'PERSON' DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION syp_protect_service_person()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.kind <> 'PERSON' AND EXISTS (SELECT 1 FROM tbl_movies WHERE id = OLD.id_movie) THEN
        RAISE EXCEPTION
            'Служебная персона «%» вида % удалению не подлежит: «нет персоны» выражается ею, а не пустой ссылкой (Р-12)',
            OLD.name, OLD.kind
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;
