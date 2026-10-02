-- Сезон — самостоятельная сущность, а не пометка у серии.
--
-- Структура произведения по решению владельца (2026-10-03):
--   сериал состоит из сезонов, сезон состоит из эпизодов.
-- У художественного фильма ни сезонов, ни эпизодов нет, и его обозначение
-- S00E00: ноль означает «сезона нет», а не «сезон забыли внести».
--
-- Поэтому season_id необязателен: у фильма его нет, а обозначение собирается
-- из нулей. Фиктивный «сезон 0» не заводится — «сезона нет» и «есть сезон
-- с номером ноль» это разные вещи, и путать их нельзя.
CREATE TABLE season (
    id           bigserial   PRIMARY KEY,
    serial_id    bigint      NOT NULL REFERENCES serial (id) ON DELETE CASCADE,
    ordinal      integer     NOT NULL,
    name         text        NOT NULL,
    recordhash   text        NOT NULL
);

-- Номер сезона начинается с единицы: ноль зарезервирован под «сезона нет».
ALTER TABLE season ADD CONSTRAINT season_ordinal_positive CHECK (ordinal > 0);
-- Сезон уникален по номеру внутри сериала.
CREATE UNIQUE INDEX season_serial_ordinal_uq ON season (serial_id, ordinal);
CREATE INDEX season_serial_idx ON season (serial_id);

ALTER TABLE series ADD COLUMN season_id bigint NULL REFERENCES season (id) ON DELETE CASCADE;
-- Номер эпизода внутри сезона; 0 — у фильма, где эпизодов нет.
ALTER TABLE series ADD COLUMN episode_ordinal integer NOT NULL DEFAULT 0;
ALTER TABLE series ADD CONSTRAINT series_episode_ordinal_non_negative CHECK (episode_ordinal >= 0);
CREATE INDEX series_season_idx ON series (season_id);

-- Номер эпизода уникален внутри сезона; для фильма season_id пуст, и тогда
-- уникальность не проверяется — сезона нет, значит и дублей не от чего ловить.
CREATE UNIQUE INDEX series_season_episode_uq
    ON series (season_id, episode_ordinal) WHERE season_id IS NOT NULL;

-- Пометка season из миграции 12 была неверна по сути: сезон не поле у серии,
-- а отдельная сущность. Убираем, чтобы в модели не осталось двух «сезонов».
ALTER TABLE series DROP COLUMN season;

COMMENT ON TABLE season IS 'Сезон сериала; у фильма сезонов нет, и серия остаётся без сезона';
COMMENT ON COLUMN series.episode_ordinal IS 'Номер эпизода внутри сезона; 0 — у фильма';
COMMENT ON COLUMN series.season_id IS 'Сезон-владелец; пуст у фильма, обозначение тогда S00E00';
