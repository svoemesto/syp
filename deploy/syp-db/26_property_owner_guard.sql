-- Проверка владельца свойства: обещание из миграции 25, которого там не было.
--
-- В 25 написано, что привязка настоящая и удаление владельца уносит его
-- свойства. На момент её применения этого в таблице не было: у полиморфного
-- владельца один внешний ключ невозможен, а шесть отдельных колонок с
-- перечислением — это уже не «ключ и значение», а конструкция сложнее самой
-- таблицы свойств. Проверка сделана триггером: это дёшево и даёт то же, что дал
-- бы внешний ключ, — свойство не может висеть на несуществующем владельце.
--
-- Обнаружено на стенде: строка со свойством сцены 999999, которой не существует,
-- принялась молча. Это ровно тот класс «проверка проходит, ничего не проверяя».
CREATE OR REPLACE FUNCTION syp_property_owner_must_exist() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner_exists BOOLEAN;
BEGIN
    CASE NEW.owner_kind
        WHEN 'PROJECT' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_projects WHERE id = NEW.owner_id) INTO owner_exists;
        WHEN 'VIDEOFILE' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_videofiles WHERE id = NEW.owner_id) INTO owner_exists;
        WHEN 'TRACK' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_videofile_tracks WHERE id = NEW.owner_id) INTO owner_exists;
        WHEN 'SHOT' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_shots WHERE id = NEW.owner_id) INTO owner_exists;
        WHEN 'SCENE' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_scenes WHERE id = NEW.owner_id) INTO owner_exists;
        WHEN 'PERSON' THEN
            SELECT EXISTS (SELECT 1 FROM tbl_persons WHERE id = NEW.owner_id) INTO owner_exists;
        ELSE
            RAISE EXCEPTION 'Вид владельца свойства «%» неизвестен', NEW.owner_kind
                USING ERRCODE = 'check_violation';
    END CASE;
    IF NOT owner_exists THEN
        RAISE EXCEPTION
            'Свойство «%» висит на несуществующем %: %', NEW.property_key, NEW.owner_kind, NEW.owner_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS property_owner_must_exist ON tbl_properties;

CREATE TRIGGER property_owner_must_exist
    BEFORE INSERT OR UPDATE ON tbl_properties
    FOR EACH ROW EXECUTE FUNCTION syp_property_owner_must_exist();

COMMENT ON FUNCTION syp_property_owner_must_exist() IS
    'Свойство не может висеть на несуществующем владельце: проверка заменяет внешний ключ, невозможный у полиморфного владельца';
