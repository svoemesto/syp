-- Фото персоны: кадр, на котором персона видна.
--
-- В старом проекте персона хранила `fileIdForPreview` и `frameNumberForPreview`:
-- кадр выбирал оператор из выделенных лиц. Здесь то же, только номер файла
-- называется `id_videofile_preview` — по принятой у нас схеме.
--
-- Рамку лица не храним: старый проект её не хранил, а показ фото идёт через
-- уже существующий кадр-превью по номеру кадра, то есть тем же путём.
ALTER TABLE tbl_persons
    ADD COLUMN IF NOT EXISTS id_videofile_preview BIGINT,
    ADD COLUMN IF NOT EXISTS frame_number_preview BIGINT;

ALTER TABLE tbl_persons
    DROP CONSTRAINT IF EXISTS syp_person_photo_videofile;

ALTER TABLE tbl_persons
    ADD CONSTRAINT syp_person_photo_videofile
    FOREIGN KEY (id_videofile_preview) REFERENCES tbl_videofiles (id);
