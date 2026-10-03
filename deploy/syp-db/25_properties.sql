-- Произвольные свойства «ключ — значение».
--
-- В старом проекте это `tbl_properties`: одна таблица на все сущности, привязка
-- строкой `parent_class` («Person», «Shot», «Scene», «Project», «File»,
-- «Track», «Event») и `parent_id` **без внешнего ключа**. Системных ключей в
-- коде не нашлось ни одного — целиком вводит оператор: схема фиксирована, а
-- дописать то, чего в ней нет, надо куда-то.
--
-- Отличия от старого проекта, по решению владельца:
--
-- - вид владельца — перечисление, а не строка с именем класса. Строкой связь
--   рвётся молча при переименовании класса в коде, и это не гипотеза: при
--   переносе старого класса в новый такой разрыв и происходит;
-- - внешние ключи настоящие, и удаление владельца уносит его свойства с собой.
--   Без этого удаление персоны оставляло бы после себя её заметки.
--
-- Записи свойства на сериале, видеофайле, дорожке, плане, сцене и персоне. Событие
-- в списке есть, но таблицы событий пока нет: владелец описал событие словами
-- («то, что происходит в сцене»), а модель обещана разобрать позже, и выдумывать
-- её под таблицу свойств нельзя.
CREATE TABLE IF NOT EXISTS tbl_properties (
    id           BIGSERIAL PRIMARY KEY,
    owner_kind   TEXT     NOT NULL,
    owner_id     BIGINT   NOT NULL,
    property_key TEXT     NOT NULL,
    property_value TEXT   NOT NULL,
    ordinal      INTEGER  NOT NULL DEFAULT 0,
    CONSTRAINT property_owner_kind_known
        CHECK (owner_kind IN ('PROJECT', 'VIDEOFILE', 'TRACK', 'SHOT', 'SCENE', 'PERSON')),
    CONSTRAINT property_key_not_blank CHECK (btrim(property_key) <> ''),
    CONSTRAINT property_key_length CHECK (length(property_key) <= 120),
    CONSTRAINT property_value_length CHECK (length(property_value) <= 4000),
    CONSTRAINT property_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT property_owner_unique UNIQUE (owner_kind, owner_id, property_key)
);

CREATE INDEX IF NOT EXISTS property_owner_idx
    ON tbl_properties (owner_kind, owner_id, ordinal);

COMMENT ON TABLE tbl_properties IS
    'Произвольные свойства: ключ и значение, привязанные к владельцу на настоящем внешнем ключе';
COMMENT ON COLUMN tbl_properties.owner_kind IS
    'Вид владельца: проект, видеофайл, дорожка, план, сцена или персона';
