package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.MovieSetting
import ru.svoemesto.syp.admin.catalog.MovieSettings
import ru.svoemesto.syp.core.db.Db

/**
 * Отбрасывание рамок, которые лицом не являются.
 *
 * Детектор находит не только лица: на вытянутых рамках — на рельсах, на
 * колоннах, на тексте по краю кадра — он тоже отвечает «есть объект». Такая
 * рамка не должна ни попасть в обучающую выборку, ни ждать имени от
 * оператора: она получает служебную персону «не лицо» и остаётся видимой
 * (research.md Т-18).
 *
 * Признак здесь один — **пропорции рамки**: отношение большей стороны к
 * меньшей. Лицо почти всегда близко к квадрату, а вытянутый прямоугольник
 * человеком не бывает.
 *
 * Порог — **настройка фильма** `face.not_person_aspect`, а не константа
 * кода. Иначе подбор порога требовал бы пересборки образа, а число, молча
 * зашитое в код, невозможно объяснить постфактум: изменилось поведение —
 * изменилось ли качество (ADR-0003).
 *
 * @property db доступ к базе сырым JDBC
 * @property persons сервис персон: он даёт служебную персону «не лицо»
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class NonPersonFilter(
    private val db: Db,
    private val persons: PersonService,
) {
    /**
     * Пропорции рамки.
     *
     * @param x1 левая граница
     * @param y1 верхняя граница
     * @param x2 правая граница
     * @param y2 нижняя граница
     * @return отношение большей стороны к меньшей
     */
    fun aspect(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): Double {
        val width = (x2 - x1).toDouble()
        val height = (y2 - y1).toDouble()
        require(width > 0 && height > 0) {
            "Рамка ($x1, $y1) — ($x2, $y2) пуста: пропорции у неё нет"
        }
        val longer = maxOf(width, height)
        val shorter = minOf(width, height)
        return longer / shorter
    }

    /**
     * Является ли рамка лицом по пропорциям.
     *
     * @param detected найденное лицо
     * @param maxAspect порог пропорции из настроек фильма
     * @return `true`, если рамка признана лицом
     */
    fun looksLikeFace(
        detected: DetectedFace,
        maxAspect: Double,
    ): Boolean {
        require(maxAspect >= 1.0) {
            "Порог пропорции меньше единицы: у любой непустой рамки пропорция не меньше 1, " +
                "задано $maxAspect"
        }
        return aspect(detected.x1, detected.y1, detected.x2, detected.y2) <= maxAspect
    }

    /**
     * Пропорция, при которой рамка признаётся лицом.
     *
     * @param settings настройки фильма
     * @return порог пропорции
     */
    fun thresholdOf(settings: MovieSettings): Double = settings.number(MovieSetting.FACE_NOT_PERSON_ASPECT)

    /**
     * Переводит лица, которые лицом не являются, в служебную персону «не лицо».
     *
     * Лицо при этом **не удаляется**: рамка остаётся на кадре и остаётся
     * видимой в интерфейсе. Удаление означало бы, что оператор не увидит
     * ложное срабатывание вовсе и не сможет его проверить, а расхождение
     * детектора с картинкой выглядело бы как «детектор ошибся» без следа.
     *
     * Перевод идёт **в одной транзакции** на всём эпизод. Промежуточное
     * состояние, где часть нелицевых рамок переведена, а часть нет, не
     * наблюдаемо через интерфейс, но наблюдаемо в отчёте «кто в сцене» —
     * а значит, попало бы в сценарий сборки.
     *
     * @param episodeId эпизод
     * @param settings настройки фильма; из них берётся порог пропорции
     * @return сколько лиц переведено в «не лицо»
     * @throws ru.svoemesto.syp.core.db.DbException если перевод не удался
     */
    fun applyEpisode(
        episodeId: Long,
        settings: MovieSettings,
    ): Int {
        val movieId =
            db.selectOne(
                "SELECT id_movie FROM tbl_episodes WHERE id = ?",
                { it.long("id_movie") },
                episodeId,
            ) ?: return 0
        val maxAspect = thresholdOf(settings)
        val nonPerson = persons.servicePerson(movieId, PersonKind.NONPERSON).id
        requireNotNull(nonPerson)
        return db.useTransaction { connection ->
            var changed = 0
            // Рамки перебираются по эпизоду: чтение всех лиц эпизода в память не
            // годится — на S01E01 их десятки тысяч.
            connection
                .prepareStatement(
                    "SELECT id, x1, y1, x2, y2, person_id FROM ${FaceStore.TABLE} WHERE id_episode = ?",
                ).use { statement ->
                    statement.setLong(1, episodeId)
                    statement.executeQuery().use { resultSet ->
                        val suspected = mutableListOf<Pair<Long, Long>>()
                        while (resultSet.next()) {
                            val faceId = resultSet.getLong("id")
                            val aspect =
                                aspect(
                                    resultSet.getInt("x1"),
                                    resultSet.getInt("y1"),
                                    resultSet.getInt("x2"),
                                    resultSet.getInt("y2"),
                                )
                            val personId = resultSet.getLong("person_id")
                            if (aspect > maxAspect && personId != nonPerson) {
                                suspected.add(faceId to personId)
                            }
                        }
                        connection
                            .prepareStatement(
                                "UPDATE ${FaceStore.TABLE} SET person_id = ?, recordhash = NULL WHERE id = ?",
                            ).use { update ->
                                suspected.forEach { (faceId, _) ->
                                    update.setLong(1, nonPerson)
                                    update.setLong(2, faceId)
                                    changed += update.executeUpdate()
                                }
                            }
                    }
                }
            changed
        }
    }

    /**
     * Возвращает персона для рамки по её пропорциям.
     *
     * @param detected найденное лицо
     * @param maxAspect порог пропорции
     * @param unrecognizedId служебная персона «распознано, имя не подтверждено»
     * @param nonPersonId служебная персона «не лицо»
     * @return какая из служебных персон соответствует рамке
     */
    fun personFor(
        detected: DetectedFace,
        maxAspect: Double,
        unrecognizedId: Long,
        nonPersonId: Long,
    ): Long = if (looksLikeFace(detected, maxAspect)) unrecognizedId else nonPersonId
}
