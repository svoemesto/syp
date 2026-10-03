-- Колонки под новые имена: продолжение миграции 21.
--
-- Миграция 21 переименовала таблицы, но не колонки: код ждал `id_project`, а в
-- базе осталось `id_movie`. Обнаружилось при развёртывании — все адреса отдали
-- 500 с «column "id_project" does not exist», и это видно только на стенде.
--
-- Схема держит идальные по именам колонки, а не только по таблицам, поэтому
-- переименование неполное без них. Порядок такой же, как у таблиц: сериал —
-- проект, видеофайл внутри сериала — видеофайл.
--
-- Имена ограничений заданы в базе, а не по шаблону Postgres, поэтому приводятся
-- явно: иначе `ALTER TABLE ... RENAME COLUMN` оставил бы «serial_id» на
-- столбце, который называется `id_project`.
-- Переименования условные: повторное применение не должно падать. Учёт миграций
-- (23) не даёт применять файл повторно, но стенд и тестовые базы приводятся к
-- одному состоянию разными путями, и повторный запуск под рукой должен быть
-- безвреден.
DO
$$
    BEGIN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'tbl_videofiles' AND column_name = 'id_movie'
        ) THEN
            EXECUTE 'ALTER TABLE tbl_videofiles RENAME COLUMN id_movie TO id_project';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'tbl_videofiles' AND column_name = 'episode_ordinal'
        ) THEN
            EXECUTE 'ALTER TABLE tbl_videofiles
                     RENAME COLUMN episode_ordinal TO videofile_ordinal';
        END IF;
    END
$$;

DO
$$
    DECLARE
        target text;
    BEGIN
        -- Внешние ключи на сериал: имя ограничения приводим к новому имени
        -- столбца, иначе «tbl_videofiles_id_movie_fkey» будет вводить в
        -- заблуждение при следующем чтении схемы.
        FOR target IN
            SELECT c.conname
            FROM pg_constraint c
            WHERE c.conrelid = 'tbl_videofiles'::regclass
                AND c.contype = 'f'
                AND c.conname LIKE '%id_movie%'
            LOOP
                EXECUTE format(
                    'ALTER TABLE tbl_videofiles RENAME CONSTRAINT %I TO %I',
                    target,
                    replace(target, 'id_movie', 'id_project')
                );
            END LOOP;
    END
$$;

-- Ссылки на видеофайл разбросаны по всей схеме; имена колонок приводятся к
-- новому слову, содержимое не трогается.
DO
$$
    DECLARE
        tbl text;
        col text;
    BEGIN
        FOR tbl, col IN
            SELECT c.relname,
                   a.attname
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
                AND c.relkind = 'r'
                AND a.attname IN ('id_episode', 'episode_name')
            LOOP
                EXECUTE format(
                    'ALTER TABLE %I RENAME COLUMN %I TO %I',
                    tbl,
                    col,
                    replace(col, 'episode', 'videofile')
                );
            END LOOP;
    END
$$;

DO
$$
    DECLARE
        tbl text;
        col text;
    BEGIN
        FOR tbl, col IN
            SELECT c.relname,
                   a.attname
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
                AND c.relkind = 'r'
                AND a.attname = 'id_movie'
                AND c.relname <> 'tbl_videofiles'
            LOOP
                EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO id_project', tbl, col);
            END LOOP;
    END
$$;

-- Функции в базе разбираются при вызове, а не при создании, поэтому переименование
-- колонки в их тело не попадает. Функция ниже вставлена в `tbl_analysis_settings`
-- колонку `id_movie` — уже переименованную, и падала бы при первом же создании
-- сериала. Нашлось это на стенде: адреса отдавали 500.
CREATE OR REPLACE FUNCTION syp_apply_default_settings()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tbl_analysis_settings (id_project, key, value) VALUES
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

-- Ещё две функции из той же миграции 16 вставляют и читают старое имя.
CREATE OR REPLACE FUNCTION syp_create_service_persons()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO tbl_persons (id_project, name, recognizer_key, kind) VALUES
        (NEW.id, 'Распознано, имя не подтверждено', NULL, 'UNRECOGNIZED'),
        (NEW.id, 'Не лицо',                          NULL, 'NONPERSON')
    ON CONFLICT (id_project, kind) WHERE kind <> 'PERSON' DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION syp_protect_service_person()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.kind <> 'PERSON'
        AND EXISTS (SELECT 1 FROM tbl_projects WHERE id = OLD.id_project) THEN
        RAISE EXCEPTION
            'Служебная персона «%» вида % удалению не подлежит',
            OLD.name, OLD.kind
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;

-- Рамка лица проверяется триггером, который тоже читает старое имя таблицы и
-- столбца. Пропущен был потому, что тело функции объявлено в одну строку после
-- имени, и поиск по шаблону многострочного объявления его не видел.
CREATE OR REPLACE FUNCTION face_box_within_series() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    frame_width  INTEGER;
    frame_height INTEGER;
BEGIN
    SELECT width, height INTO frame_width, frame_height
      FROM tbl_videofiles
     WHERE id = NEW.id_videofile;

    IF frame_width IS NULL THEN
        RAISE EXCEPTION
            'Видеофайл % не найден: рамку лица сохранить не на чем',
            NEW.id_videofile
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.x1 < 0 OR NEW.y1 < 0 OR NEW.x2 > frame_width OR NEW.y2 > frame_height THEN
        RAISE EXCEPTION
            'Рамка лица (%, %) — (%, %) выходит за пределы кадра %×%',
            NEW.x1, NEW.y1, NEW.x2, NEW.y2, frame_width, frame_height
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

-- Версия формата сценария: триггер из миграции 16 ставил 1, миграция 18 подняла
-- до 2 и обновила строки, созданные до неё. Ручные правки функции вернули 1, и
-- стенд остался с ней. Код объявляет 2, и проверка на это прямо смотрит.
UPDATE tbl_analysis_settings
   SET value = '2'::jsonb
 WHERE key = 'recipe.schema_version'
   AND value <> '2'::jsonb;

UPDATE tbl_analysis_settings
   SET value = '[0.35, 0.22, 0.13, 0.075, 0.04, 0.02, 0.009, 0.003]'::jsonb
 WHERE key = 'shot.size.thresholds'
   AND jsonb_array_length(value) <> 8;
