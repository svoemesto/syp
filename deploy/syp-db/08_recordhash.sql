-- 08_recordhash.sql — SYP, PostgreSQL 16
--
-- Столбец `recordhash` во всех таблицах модели: хеш значений строки, по
-- которому сохранение понимает, изменилась ли строка.
--
-- Зачем: персистентность проекта — сырой JDBC по образцу `KaraokeDbTable`
-- (constitution III, AGENTS.md § Tier-1 «Стек и запреты»): сохранение
-- считает разницу по значениям и пишет хеш, а не безусловно перезаписывает
-- строку целиком. Это даёт три вещи:
--
--   1. запись идёт только при реальном изменении — меньше мёртвых записей
--      и меньше конфликтов при параллельной работе;
--   2. по хешу видно, что строку меняли: расхождение хешей — признак
--      того, что две правки одного объекта столкнулись;
--   3. перезагрузка объекта не считается изменением: хеш совпадает,
--      записи нет.
--
-- Миграция добавочная и применяется один раз после 07_settings.sql.
-- Значение NULL означает «строка ещё не записывалась через diff-save»
-- (например, вставлена миграцией или вручную): это допустимое состояние,
-- и первое же сохранение его заполнит.
--
-- Обратная совместимость: существующие таблицы не меняются по смыслу,
-- добавляется один столбец, не участвующий ни в одном ограничении.
--
-- Происхождение: задача T019 фазы 0 спеки первого среза. Поля этого столбца
-- в data-model.md (раздел 2) не перечислены: модель описывает предметные
-- поля сущностей, а `recordhash` — служебное поле механизма сохранения.

ALTER TABLE serial ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE location ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE series ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE analysis_run ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE raw_boundary ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE frame ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE scene ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE shot ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE person ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE model_version ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE face ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE face_embedding ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE model_version_example ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE syp_filter ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE filter_group ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE filter_condition ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE job ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE artifact ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE source_file_checksum ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE build_recipe ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE build_recipe_item ADD COLUMN IF NOT EXISTS recordhash TEXT;
ALTER TABLE analysis_setting ADD COLUMN IF NOT EXISTS recordhash TEXT;

COMMENT ON COLUMN job.recordhash IS
    'SHA-256 значений строки в каноническом виде. Служебное поле механизма сохранения (constitution III): NULL, если строка ещё не записывалась через diff-save.';
COMMENT ON COLUMN artifact.recordhash IS
    'SHA-256 значений строки в каноническом виде. Служебное поле механизма сохранения (constitution III).';
