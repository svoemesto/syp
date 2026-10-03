-- Проект и видеофайл: имена владельца от 2026-10-03.
--
-- Зачем: в старом проекте «проект» — это сериал, а «файл» значил и видеофайл
-- серии, и запись о нём. Одно слово — два предмета, и путаница была заложена в
-- словарь, а не случилась. Разводим: `tbl_projects` — сериал,
-- `tbl_videofiles` — видеофайл внутри сериала, а запись о нём лежит в
-- `tbl_source_file_checksums` и ни с чем не спорит.
--
-- Сезон уходит из отдельной таблицы: владелец решил, что это число в записи
-- видеофайла. Связь `season_id` снимается каскадом вместе с колонкой — иначе
-- таблицу не удалить. Данных терять нечего: в `tbl_seasons` ноль строк, а в
-- колонке висел только этот ключ.
--
-- Миграция 16, создавшая `tbl_seasons`, остаётся нетронутой: миграции
-- дописываются, а не переписываются.
ALTER TABLE tbl_episodes DROP COLUMN IF EXISTS season_id CASCADE;

DROP TABLE IF EXISTS tbl_seasons;

ALTER TABLE tbl_movies RENAME TO tbl_projects;

ALTER TABLE tbl_episodes RENAME TO tbl_videofiles;

ALTER TABLE tbl_videofiles
    ADD COLUMN IF NOT EXISTS season_number INTEGER;

COMMENT ON COLUMN tbl_videofiles.season_number IS
    'Номер сезона внутри сериала; числом, по нему сериал упорядочивается';
