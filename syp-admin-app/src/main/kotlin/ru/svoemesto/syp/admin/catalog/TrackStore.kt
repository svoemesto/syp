package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.MediaTrack

/**
 * Хранилище дорожек видеофайла.
 *
 * **Зачем хранить, а не спрашивать зонд.** Видеофайл — это единицы гигабайт, а
 * список дорожек нужен при каждом открытии: показать в интерфейсе, повесить на
 * дорожку свойство. Зонд при этом файл не перечитывается: список определён
 * один раз при приёме и лежит здесь.
 *
 * @property db соединение с базой
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class TrackStore(
    private val db: Db,
) {
    /**
     * Записывает дорожки видеофайла, заменяя прежний список.
     *
     * Замена целиком, а не дописывание: определение повторяется, и частичное
     * обновление оставило бы дорожки от прошлого раза, которых в файле уже нет.
     *
     * @param videofileId видеофайл
     * @param tracks дорожки как их насчитал зонд
     * @return сколько дорожек записано
     */
    fun replace(
        videofileId: Long,
        tracks: List<MediaTrack>,
    ): Int =
        db.use { connection ->
            connection.prepareStatement("DELETE FROM $TABLE WHERE id_videofile = ?").use { statement ->
                statement.setLong(1, videofileId)
                statement.executeUpdate()
            }
            if (tracks.isEmpty()) {
                return@use 0
            }
            connection
                .prepareStatement(
                    "INSERT INTO $TABLE (id_videofile, track_index, ordinal, codec_type, codec_name) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    tracks.forEach { track ->
                        statement.setLong(1, videofileId)
                        statement.setInt(2, track.index)
                        statement.setInt(3, track.ordinal)
                        statement.setString(4, track.codecType)
                        statement.setString(5, track.codecName)
                        statement.addBatch()
                    }
                    statement.executeBatch().sum()
                }
        }

    /**
     * Читает дорожки видеофайла по возрастанию номера.
     *
     * @param videofileId видеофайл
     * @return дорожки; пусто, если они не определены
     */
    fun list(videofileId: Long): List<MediaTrack> =
        db.use { connection ->
            connection
                .prepareStatement(
                    "SELECT track_index, ordinal, codec_type, codec_name FROM $TABLE " +
                        "WHERE id_videofile = ? ORDER BY track_index",
                ).use { statement ->
                    statement.setLong(1, videofileId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    MediaTrack(
                                        index = rows.getInt("track_index"),
                                        ordinal = rows.getInt("ordinal"),
                                        codecType = rows.getString("codec_type"),
                                        codecName = rows.getString("codec_name"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private companion object {
        /** Таблица дорожек. */
        const val TABLE: String = "tbl_videofile_tracks"
    }
}
