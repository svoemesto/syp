-- 11_service_persons.sql — SYP, PostgreSQL 16
--
-- Служебные персоны-заглушки: «распознано, но имя не подтверждено» и «не
-- лицо». Создаются для каждого фильма и удалению не подлежат.
--
-- Зачем: «нет персоны» выражается **заглушкой**, а не пустой ссылкой
-- (Р-12, FR-033, FR-036). Лицо всегда ссылается на персону: `face.person_id`
-- объявлен `NOT NULL`, но сама заглушка должна существовать всегда, иначе
-- первое же новое лицо упало бы с нарушением внешнего ключа, а оператор
-- увидел бы «внутреннюю ошибку сервера» вместо «персоны нет».
--
-- Механизм тот же, что у настроек фильма (миграция 07): заполнение для уже
-- существующих фильмов выполняется здесь, а новому фильму заглушки заводит
-- триггер. Правило живёт в базе, а не в коде контроллера: фильм может быть
-- заведён мимо админки, и заглушек должно быть два в любом случае.
--
-- Спецификация: FR-033, FR-036. Задача: T069.
-- Модель: specs/001-first-vertical-slice/data-model.md, раздел 2.9.
-- Миграция добавочная, применяется один раз после 10_checksum_job_link.sql.

-- Ровно одна служебная персона каждого вида на фильм. Частичный индекс:
-- у обычных персон вид `PERSON`, и ограничение на них не распространяется —
-- обычных персон у фильма сколько угодно.
CREATE UNIQUE INDEX person_service_kind_unique_idx
    ON person (serial_id, kind)
    WHERE kind <> 'PERSON';

COMMENT ON INDEX person_service_kind_unique_idx IS
    'Не более одной служебной персоны каждого вида на фильм: заглушка одна, иначе «нет персоны» снова можно было бы выразить двумя способами.';

-- Заглушки для фильмов, заведённых до этой миграции.
INSERT INTO person (serial_id, name, recognizer_key, kind)
SELECT s.id, v.name, NULL, v.kind
FROM serial s
CROSS JOIN (VALUES
    ('Распознано, имя не подтверждено', 'UNRECOGNIZED'),
    ('Не лицо',                          'NONPERSON')
) AS v(name, kind)
ON CONFLICT (serial_id, kind) WHERE kind <> 'PERSON' DO NOTHING;

COMMENT ON TABLE person IS
    'Персона — именованная личность. Служебные виды UNRECOGNIZED и NONPERSON — заглушки, а не ошибка (Р-12).';

-- Новый фильм сразу получает обе заглушки.
CREATE OR REPLACE FUNCTION syp_create_service_persons()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO person (serial_id, name, recognizer_key, kind) VALUES
        (NEW.id, 'Распознано, имя не подтверждено', NULL, 'UNRECOGNIZED'),
        (NEW.id, 'Не лицо',                          NULL, 'NONPERSON')
    ON CONFLICT (serial_id, kind) WHERE kind <> 'PERSON' DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER serial_create_service_persons
    AFTER INSERT ON serial
    FOR EACH ROW EXECUTE FUNCTION syp_create_service_persons();

COMMENT ON FUNCTION syp_create_service_persons() IS
    'Каждый новый фильм получает обе служебные персоны: без них первое же лицо нарушает внешний ключ (Р-12).';

-- Служебная персона удалению не подлежит: пока жив фильм, заглушка на
-- месте, и «нет персоны» нельзя выразить ни пустой ссылкой, ни её удалением.
--
-- Исключение одно — удаление самого фильма. Каскад от `serial` срабатывает
-- **после** удаления строки фильма, поэтому проверка «жив ли фильм» отличает
-- попытку удалить заглушку от каскадного удаления. Без этой проверки удалить
-- фильм было бы нельзя, а удаление фильма — обычная операция админки.
CREATE OR REPLACE FUNCTION syp_protect_service_person()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.kind <> 'PERSON' AND EXISTS (SELECT 1 FROM serial WHERE id = OLD.serial_id) THEN
        RAISE EXCEPTION
            'Служебная персона «%» вида % удалению не подлежит: «нет персоны» выражается ею, а не пустой ссылкой (Р-12)',
            OLD.name, OLD.kind
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER person_protect_service
    BEFORE DELETE ON person
    FOR EACH ROW EXECUTE FUNCTION syp_protect_service_person();

COMMENT ON FUNCTION syp_protect_service_person() IS
    'Запрет удаления служебных персон при живом фильме. При удалении фильма заглушки уносятся каскадом.';
