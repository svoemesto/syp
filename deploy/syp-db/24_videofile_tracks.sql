-- Дорожки видеофайла.
--
-- Зачем: в старом проекте `tbl_files_tracks` есть, и дорожки — полноправная
-- часть файла, а не деталь разбора. Свойства в старом проекте вешаются и на
-- дорожку тоже.
--
-- Определяются автоматически зондом, но **сохраняются**: файл каждый раз зондом
-- не трогаем. Видеофайл — это 5 гигабайт и десятки тысяч кадров, а список
-- дорожек нужен на каждом открытии файла.
--
-- Ключ `(id_videofile, track_index)`: у видеофайла дорожка одна с таким номером,
-- и повторное определение после переустановки не плодит дубли.
CREATE TABLE IF NOT EXISTS tbl_videofile_tracks (
    id            BIGSERIAL PRIMARY KEY,
    id_videofile  BIGINT    NOT NULL,
    track_index   INTEGER   NOT NULL,
    ordinal       INTEGER   NOT NULL,
    codec_type    TEXT      NOT NULL,
    codec_name    TEXT,
    CONSTRAINT videofile_track_unique UNIQUE (id_videofile, track_index),
    CONSTRAINT videofile_track_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT videofile_track_type_known
        CHECK (codec_type IN ('video', 'audio', 'subtitle', 'data', 'attachment'))
);

ALTER TABLE tbl_videofile_tracks
    ADD CONSTRAINT videofile_track_videofile_fk
    FOREIGN KEY (id_videofile) REFERENCES tbl_videofiles (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS videofile_track_videofile_idx
    ON tbl_videofile_tracks (id_videofile, ordinal);

COMMENT ON TABLE tbl_videofile_tracks IS
    'Дорожки видеофайла: определены зондом при приёме и сохранены, чтобы файл зондом не перечитывать';
