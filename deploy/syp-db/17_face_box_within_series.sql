-- 17_face_box_within_series.sql — SYP, PostgreSQL 16
--
-- Рамка лица обязана помещаться в кадр своей серии.
--
-- Спецификация: FR-034, задача T064.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.10.
--
-- Что уже обеспечено миграцией 03:
--   * face_box_order CHECK (x1 < x2 AND y1 < y2) — рамка не перевёрнута;
--   * face_natural_key_unique — серия, номер кадра и порядковый номер лица
--     образуют естественный ключ.
--
-- Чего там нет: проверки попадания рамки **в разрешение серии**. Условие
-- CHECK этого не умеет — оно не видит других таблиц, а разрешение кадра
-- лежит в `series.width` и `series.height`. Поэтому проверка делается
-- триггером: рамка, вышедшая за кадр, означает, что детектор работал с
-- размерами, которых у кадра нет, и такая строка не должна появиться в базе
-- ни через один путь записи.
--
-- Миграция добавочная, применяется один раз после 16_tbl_prefix_rename.sql:
-- появление после 15_scene_title.sql относится к прежней нумерации, а
-- тело функции и триггер приведены к именам, которые даёт переименование
-- таблиц (tbl_episodes, tbl_faces, id_episode).
--
-- Почему триггер, а не код на записи: правило «рамка вне кадра — отказ»
-- проверяется только тогда, когда его нельзя обойти. Код на записи
-- обходится прямым INSERT из отчёта, из другого сервиса или из ручной
-- правки в psql — а лицо читается на каждом показе, и испорченная рамка
-- вернулась бы в интерфейс как рамка лица.

CREATE OR REPLACE FUNCTION face_box_within_series() RETURNS TRIGGER AS $$
DECLARE
    frame_width  INTEGER;
    frame_height INTEGER;
BEGIN
    SELECT width, height INTO frame_width, frame_height
      FROM tbl_episodes
     WHERE id = NEW.id_episode;

    IF frame_width IS NULL THEN
        RAISE EXCEPTION
            'Серия % не найдена: рамку лица сохранить некуда', NEW.id_episode
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.x1 < 0 OR NEW.y1 < 0 OR NEW.x2 > frame_width OR NEW.y2 > frame_height THEN
        RAISE EXCEPTION
            'Рамка лица (%, %) — (%, %) выходит за пределы кадра %x%',
            NEW.x1, NEW.y1, NEW.x2, NEW.y2, frame_width, frame_height
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION face_box_within_series() IS
    'Проверка рамки лица: не перевёрнута (face_box_order) и помещается в разрешение серии.';

DROP TRIGGER IF EXISTS face_box_within_series_trg ON tbl_faces;

CREATE TRIGGER face_box_within_series_trg
    BEFORE INSERT OR UPDATE OF x1, y1, x2, y2, id_episode ON tbl_faces
    FOR EACH ROW EXECUTE FUNCTION face_box_within_series();
