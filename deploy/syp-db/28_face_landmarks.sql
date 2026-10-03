-- Пять точек лица от детектора.
--
-- YuNet выдаёт вместе с рамкой пять точек: глаза, нос, два угла рта. Эмбеддер
-- распознавания учится на лицах, выровненных по этим точкам, поэтому без них
-- вектор получается мусором: модель ждет определённую геометрию лица, а получает
-- рамку, растянутую как попало.
--
-- Точки хранятся рядом с рамкой, а не вместо неё: рамка нужна для показа лица
-- оператору, точки — для эмбеддинга. И то и другое приходит из одного выхода
-- детектора.
--
-- Столбцы nullable: лица, найденные до этой миграции, точек не имеют. Такие
-- лица вектора не получают, и это видно, а не спрятано.
ALTER TABLE tbl_faces
    ADD COLUMN IF NOT EXISTS eye_left_x INTEGER,
    ADD COLUMN IF NOT EXISTS eye_left_y INTEGER,
    ADD COLUMN IF NOT EXISTS eye_right_x INTEGER,
    ADD COLUMN IF NOT EXISTS eye_right_y INTEGER,
    ADD COLUMN IF NOT EXISTS nose_x INTEGER,
    ADD COLUMN IF NOT EXISTS nose_y INTEGER,
    ADD COLUMN IF NOT EXISTS mouth_left_x INTEGER,
    ADD COLUMN IF NOT EXISTS mouth_left_y INTEGER,
    ADD COLUMN IF NOT EXISTS mouth_right_x INTEGER,
    ADD COLUMN IF NOT EXISTS mouth_right_y INTEGER;
