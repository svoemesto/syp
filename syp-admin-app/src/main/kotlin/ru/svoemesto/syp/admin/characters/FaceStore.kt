package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table

/**
 * Лицо, найденное в кадре эпизода.
 *
 * Лицо — это **рамка в координатах кадра полного разрешения**. Время здесь
 * не хранится: номер кадра — единственный источник правды для границ
 * (ADR-0001), а клиент пересчитывает время от `time_base` эпизода.
 *
 * Естественный ключ лица — тройка **эпизод, номер кадра, порядковый номер в
 * кадре**. Он же объявлен уникальным в базе (`face_natural_key_unique`).
 * Смысл порядкового номера — в том, что он задаётся детектором: два
 * повторных прохода по одному эпизоду дают по нескольку рамок на один и тот же
 * кадр, и без порядкового номера вторая запись конфликтовала бы с первой.
 *
 * Рамка проверяется дважды, и обе проверки обязательны:
 *
 * 1. **на записи** — перевёрнутая или пустая рамка не доходит до базы
 *    (`DetectedFace`, ограничение `face_box_order`);
 * 2. **в базе** — рамка обязана помещаться в разрешение эпизода
 *    (миграция `16_face_box_within_episode.sql`). Условие `CHECK` этого не
 *    умеет: разрешение лежит в другой таблице.
 *
 * @property id идентификатор лица; `null`, пока не записано
 * @property episodeId эпизод-владелец
 * @property frameNumber номер кадра, нумерация с нуля
 * @property faceIndex порядковый номер лица в кадре, с нуля
 * @property x1 левая граница рамки в пикселях кадра
 * @property y1 верхняя граница рамки в пикселях кадра
 * @property x2 правая граница рамки в пикселях кадра
 * @property y2 нижняя граница рамки в пикселях кадра
 * @property shotId план, которому принадлежит лицо; `null` допустим, только
 *   если номер кадра вне диапазонов всех планов эпизода (FR-034)
 * @property personId персона лица; непустая **всегда**, «нет персоны»
 *   выражается служебной заглушкой (Р-12, FR-036)
 * @property origin происхождение рамки: `AUTO` или `OPERATOR` (FR-032)
 * @property isExample помечено ли лицо эталоном для обучения (FR-060)
 * @property detectConfidence уверенность детектора, от 0 до 1
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Face(
    val id: Long? = null,
    val episodeId: Long,
    val frameNumber: Int,
    val faceIndex: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val shotId: Long? = null,
    val personId: Long,
    val origin: FaceOrigin = FaceOrigin.AUTO,
    val isExample: Boolean = false,
    val detectConfidence: Double? = null,
    val recordHash: String? = null,
) {
    init {
        require(frameNumber >= 0) {
            "Номер кадра лица не может быть отрицательным, задано $frameNumber"
        }
        require(faceIndex >= 0) {
            "Порядковый номер лица в кадре не может быть отрицательным, задано $faceIndex"
        }
        require(x1 < x2 && y1 < y2) {
            "Рамка лица перевёрнута или пуста: ($x1, $y1) — ($x2, $y2)"
        }
        if (detectConfidence != null) {
            require(detectConfidence in 0.0..1.0) {
                "Уверенность детектора вне диапазона 0…1: $detectConfidence"
            }
        }
    }

    /** Ширина рамки в пикселях кадра. */
    val width: Int get() = x2 - x1

    /** Высота рамки в пикселях кадра. */
    val height: Int get() = y2 - y1

    /**
     * Проверяет, что рамка помещается в кадр.
     *
     * @param frameWidth ширина кадра
     * @param frameHeight высота кадра
     * @throws IllegalArgumentException если рамка выходит за пределы кадра
     */
    fun requireInside(
        frameWidth: Int,
        frameHeight: Int,
    ) {
        require(x1 >= 0 && y1 >= 0 && x2 <= frameWidth && y2 <= frameHeight) {
            "Рамка лица ($x1, $y1) — ($x2, $y2) выходит за пределы кадра " +
                "${frameWidth}x$frameHeight"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * Столбцы распознавания и вероятности в таблицу не входят: заполняет их
     * обучение, а не детекция (ADR-0004, FR-063). Детектор их не читает и не
     * пишет — иначе рамка, найденная новым детектором, затирала бы метку,
     * выданную прошлой версией модели.
     *
     * @return таблица с записываемыми столбцами лица
     */
    fun toTable(): Table =
        Table(
            FaceStore.TABLE,
            FaceStore.COLUMNS,
            {
                listOf(
                    episodeId,
                    frameNumber,
                    faceIndex,
                    x1,
                    y1,
                    x2,
                    y2,
                    shotId,
                    personId,
                    origin.name,
                    isExample,
                    detectConfidence,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы лица в порядке чтения из базы. */
        val READ_COLUMNS: String =
            "id, id_episode, frame_number, face_index, x1, y1, x2, y2, shot_id, person_id, " +
                "origin, is_example, detect_confidence, recordhash"
    }
}

/**
 * Происхождение рамки лица.
 *
 * Разделение обязательное: лицо, нарисованное мышью оператора (FR-032),
 * повторным анализом **не удаляется** и не заменяется результатом
 * автоматики (SC-006). Без отдельного значения нарисованное лицо было бы
 * неотличимо от найденного машиной, и перезапуск анализа уничтожил бы ручную
 * правку.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class FaceOrigin {
    /** Рамку нашёл детектор. */
    AUTO,

    /** Рамку нарисовал оператор мышью. */
    OPERATOR,
    ;

    companion object {
        /**
         * Разбирает происхождение из строки базы.
         *
         * @param value значение столбца `origin`
         * @return происхождение рамки
         * @throws IllegalArgumentException если значения нет в наборе: молча
         *   подставить `AUTO` значило бы выдать нарисованное лицо за
         *   найденное машиной, а оно удаляется следующим анализом
         */
        fun parse(value: String): FaceOrigin =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестное происхождение рамки лица: «$value»")
    }
}

/**
 * Хранилище лиц эпизода.
 *
 * Запись идёт **пачками по кадру**, а не по одному лицу: на 88 643 кадрах эпизода
 * это до нескольких сотен тысяч строк, и запись по одной строке на лицо
 * держала бы соединение открытым на часы.
 *
 * Два свойства хранилища, которые иначе выполнялись бы «по памяти»
 * вызывающих:
 *
 * 1. **естественный ключ — эпизод, кадр, порядковый номер.** Повторный
 *    проход детектора по того же эпизода обновляет те же строки, а не плодит
 *    вторые: рамка на кадре 60 с номером 1 — та же рамка, что и в прошлом
 *    проходе, даже если детектор другой;
 * 2. **лицо с происхождением `OPERATOR` повторным проходом не затирается.**
 *    Строки, которые в прошлом проходе нарисовал человек, остаются как были;
 *    обновляются только те, чьё происхождение `AUTO`.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceStore(
    private val db: Db,
) {
    /**
     * Записывает найденные лица кадра.
     *
     * Запись идёт в одну транзакцию на кадр: половинный кадр в базе означал
     * бы, что оператор увидит половину лиц кадра и не заметит этого — в
     * матрице кадров пропуск одного лица неотличим от кадра без второго
     * лица.
     *
     * Строки, нарисованные оператором, не обновляются: обновление заменило бы
     * рамку человека результатом автоматики (SC-006).
     *
     * @param episodeId эпизод-владелец
     * @param frameNumber номер кадра
     * @param found лица, найденные детектором, в порядке отдачи
     * @param personId персона по умолчанию для найденных лиц: служебная
     *   заглушка «распознано, имя не подтверждено»
     * @param frameWidth ширина кадра: рамка проверяется на попадание в него
     * @param frameHeight высота кадра
     * @return сколько лиц записано или обновлено
     * @throws IllegalArgumentException если рамка выходит за пределы кадра
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun saveFrame(
        episodeId: Long,
        frameNumber: Int,
        found: List<DetectedFace>,
        personOf: (DetectedFace) -> Long,
        frameWidth: Int,
        frameHeight: Int,
    ): Int {
        require(frameNumber >= 0) { "Номер кадра не может быть отрицательным, задано $frameNumber" }
        found.forEach { it.requireInside(frameWidth, frameHeight) }
        if (found.isEmpty()) return 0
        return db.useTransaction { connection ->
            saveFrameInConnection(
                connection,
                episodeId,
                frameNumber,
                found,
                personOf,
                frameWidth,
                frameHeight,
            )
        }
    }

    /**
     * Перезаписывает лица кадра, найденные детектором, не трогая нарисованных.
     *
     * Метод отвечает на вопрос «что осталось от прошлого прохода»: лица
     * прежнего прохода, которых в новом нет, помечаются устаревшими удалением
     * строк — но только те, чьё происхождение `AUTO`. Нарисованные человеком
     * остаются (FR-032, SC-006).
     *
     * @param episodeId эпизод-владелец
     * @param frameNumber номер кадра
     * @param found лица, найденные детектором в этом проходе
     * @param personOf назначает персону каждому найденному лицу
     * @param frameWidth ширина кадра
     * @param frameHeight высота кадра
     * @return сколько строк лица изменилось
     */
    fun replaceAutoFrame(
        episodeId: Long,
        frameNumber: Int,
        found: List<DetectedFace>,
        personOf: (DetectedFace) -> Long,
        frameWidth: Int,
        frameHeight: Int,
    ): Int =
        db.useTransaction { connection ->
            connection
                .prepareStatement(
                    "DELETE FROM $TABLE WHERE id_episode = ? AND frame_number = ? AND origin = ?",
                ).use { statement ->
                    statement.setLong(1, episodeId)
                    statement.setInt(2, frameNumber)
                    statement.setString(3, FaceOrigin.AUTO.name)
                    statement.executeUpdate()
                }
            saveFrameInConnection(
                connection,
                episodeId,
                frameNumber,
                found,
                personOf,
                frameWidth,
                frameHeight,
            )
        }

    /**
     * Записывает лицо, нарисованное оператором.
     *
     * @param episodeId эпизод-владелец
     * @param frameNumber номер кадра
     * @param faceIndex порядковый номер лица в кадре
     * @param detected рамка
     * @param personId персона лица
     * @param frameWidth ширина кадра
     * @param frameHeight высота кадра
     * @return идентификатор записанного лица
     */
    fun saveOperatorFace(
        episodeId: Long,
        frameNumber: Int,
        faceIndex: Int,
        detected: DetectedFace,
        personId: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): Long {
        require(faceIndex >= 0) { "Порядковый номер лица не может быть отрицательным, задано $faceIndex" }
        detected.requireInside(frameWidth, frameHeight)
        val face =
            Face(
                episodeId = episodeId,
                frameNumber = frameNumber,
                faceIndex = faceIndex,
                x1 = detected.x1,
                y1 = detected.y1,
                x2 = detected.x2,
                y2 = detected.y2,
                personId = personId,
                origin = FaceOrigin.OPERATOR,
            )
        return db.useTransaction { connection ->
            Save.insertIfAbsent(connection, face.toTable())
            idOf(
                findInConnection(connection, episodeId, frameNumber, faceIndex),
                "Лицо $episodeId/$frameNumber/$faceIndex записано, но не читается",
            )
        }
    }

    /**
     * Читает лица эпизода по возрастанию номера кадра и порядкового номера.
     *
     * @param episodeId идентификатор эпизода
     * @param offset сколько лиц пропустить
     * @param limit сколько лиц вернуть; `0` — все
     * @return лица выборки
     */
    fun listByEpisode(
        episodeId: Long,
        offset: Int = 0,
        limit: Int = 0,
    ): List<Face> {
        require(offset >= 0) { "Смещение выборки не может быть отрицательным, задано $offset" }
        require(limit >= 0) { "Размер выборки не может быть отрицательным, задано $limit" }
        // Значения limit и offset подставляются в текст выборки, а не как
        // параметры: в PostgreSQL параметром может быть и `LIMIT`, но
        // подстановка числа здесь безопасна — оба значения проверены на
        // неотрицательность целым числом, и попасть в текст может только
        // цифра. Значения рядов, наоборот, идут параметром.
        val tail =
            if (limit > 0) {
                " LIMIT $limit"
            } else {
                "" +
                    if (offset > 0) " OFFSET $offset" else ""
            }
        val order = " ORDER BY frame_number, face_index"
        return db.select("$SELECT_ALL WHERE id_episode = ?$order$tail", ::readRow, episodeId)
    }

    /**
     * Читает лицо по идентификатору.
     *
     * @param faceId идентификатор лица
     * @return лицо или `null`, если его нет
     */
    fun find(faceId: Long): Face? = db.selectOne(SELECT_BY_ID, ::readRow, faceId)

    /**
     * Считает лица эпизода.
     *
     * @param episodeId идентификатор эпизода
     * @return число лиц эпизода
     */
    fun countByEpisode(episodeId: Long): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM $TABLE WHERE id_episode = ?",
            { it.int("total") },
            episodeId,
        ) ?: 0

    /**
     * Назначает лица персоне.
     *
     * Запись идёт одной транзакцией: промежуточное состояние, где часть
     * лиц уже переведена, а часть ещё нет, наблюдаемо через интерфейс и
     * попало бы в отчёт «кто в сцене» (FR-033).
     *
     * Служебный столбец `recordhash` очищается: значение лица изменилось, а
     * хеш без пересчёта означал бы «строка не менялась», и следующая
     * диффированная запись потеряла бы это изменение.
     *
     * @param personId персона-получатель
     * @param faceIds лица
     * @return сколько строк лица изменилось
     */
    fun assignPerson(
        personId: Long,
        faceIds: List<Long>,
    ): Int {
        if (faceIds.isEmpty()) return 0
        return db.useTransaction { connection ->
            var changed = 0
            connection
                .prepareStatement(
                    "UPDATE $TABLE SET person_id = ?, recordhash = NULL WHERE id = ?",
                ).use { statement ->
                    faceIds.forEach { faceId ->
                        statement.setLong(1, personId)
                        statement.setLong(2, faceId)
                        changed += statement.executeUpdate()
                    }
                }
            changed
        }
    }

    /**
     * Ставит или снимает метку эталона на лицах.
     *
     * Эталон — лицо, на котором оператор подтверждает, что это знакомый
     * человек. Само обучение модели этим флагом пользуется, поэтому метка
     * обязана ставиться вручную и сниматься так же: автоматически проставленный
     * эталон обучил бы модель на собственном предположении.
     *
     * @param faceIds идентификаторы лиц
     * @param isExample новое значение метки
     * @return сколько лиц реально изменилось
     */
    fun markExamples(
        faceIds: List<Long>,
        isExample: Boolean,
    ): Int {
        if (faceIds.isEmpty()) return 0
        return db.useTransaction { connection ->
            var changed = 0
            connection
                .prepareStatement(
                    "UPDATE $TABLE SET is_example = ?, recordhash = NULL WHERE id = ?",
                ).use { statement ->
                    faceIds.forEach { faceId ->
                        statement.setBoolean(1, isExample)
                        statement.setLong(2, faceId)
                        changed += statement.executeUpdate()
                    }
                }
            changed
        }
    }

    /**
     * Читает лица эпизода по идентификаторам.
     *
     * @param faceIds идентификаторы лиц
     * @return лица по возрастанию номера кадра
     */
    fun listByIds(faceIds: List<Long>): List<Face> {
        if (faceIds.isEmpty()) return emptyList()
        val marks = faceIds.joinToString(", ") { "?" }
        return db.select(
            "$SELECT_ALL WHERE id IN ($marks) ORDER BY frame_number, face_index",
            ::readRow,
            *faceIds.toTypedArray(),
        )
    }

    /**
     * Удаляет лицо и его эмбеддинг каскадом.
     *
     * @param faceId идентификатор лица
     * @return `true`, если лицо было удалено
     */
    fun delete(faceId: Long): Boolean =
        db.useTransaction { connection ->
            connection
                .prepareStatement("DELETE FROM $TABLE WHERE id = ?")
                .use { statement ->
                    statement.setLong(1, faceId)
                    statement.executeUpdate() > 0
                }
        }

    /**
     * Записывает лица кадра в уже открытой транзакции.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод-владелец
     * @param frameNumber номер кадра
     * @param found лица, найденные детектором
     * @param personOf назначает персону каждому найденному лицу
     * @param frameWidth ширина кадра
     * @param frameHeight высота кадра
     * @return сколько строк записано
     */
    fun saveFrameInConnection(
        connection: java.sql.Connection,
        episodeId: Long,
        frameNumber: Int,
        found: List<DetectedFace>,
        personOf: (DetectedFace) -> Long,
        frameWidth: Int,
        frameHeight: Int,
    ): Int {
        var written = 0
        found.forEachIndexed { index, detected ->
            val face =
                Face(
                    episodeId = episodeId,
                    frameNumber = frameNumber,
                    faceIndex = index,
                    x1 = detected.x1,
                    y1 = detected.y1,
                    x2 = detected.x2,
                    y2 = detected.y2,
                    personId = personOf(detected),
                    origin = FaceOrigin.AUTO,
                    detectConfidence = detected.confidence,
                )
            if (Save.insertIfAbsent(connection, face.toTable())) written++
        }
        return written
    }

    /**
     * Строит лицо из типизированной строки выборки.
     *
     * @param row строка выборки
     * @return лицо
     */
    private fun readRow(row: Row): Face =
        Face(
            id = row.long("id"),
            episodeId = row.long("id_episode"),
            frameNumber = row.int("frame_number"),
            faceIndex = row.int("face_index"),
            x1 = row.int("x1"),
            y1 = row.int("y1"),
            x2 = row.int("x2"),
            y2 = row.int("y2"),
            shotId = row.longOrNull("shot_id"),
            personId = row.long("person_id"),
            origin = FaceOrigin.parse(row.string("origin")),
            isExample = row.booleanOrNull("is_example") == true,
            detectConfidence = row.doubleOrNull("detect_confidence"),
            recordHash = row.stringOrNull(Table.RECORD_HASH_COLUMN),
        )

    /**
     * Читает лицо по естественному ключу в открытой транзакции.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод-владелец
     * @param frameNumber номер кадра
     * @param faceIndex порядковый номер лица в кадре
     * @return лицо или `null`, если такой строки нет
     */
    private fun findInConnection(
        connection: java.sql.Connection,
        episodeId: Long,
        frameNumber: Int,
        faceIndex: Int,
    ): Face? =
        connection
            .prepareStatement(
                "$SELECT_ALL WHERE id_episode = ? AND frame_number = ? AND face_index = ?",
            ).use { statement ->
                statement.setLong(1, episodeId)
                statement.setInt(2, frameNumber)
                statement.setInt(3, faceIndex)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) readRow(Row(resultSet)) else null
                }
            }

    /**
     * Требует найденное лицо.
     *
     * @param face лицо или `null`
     * @param what что ожидалось
     * @return идентификатор лица
     * @throws ru.svoemesto.syp.core.db.DbException если лицо не найдено после
     *   записи: это расхождение данных, а не пустой результат
     */
    private fun idOf(
        face: Face?,
        what: String,
    ): Long =
        face?.id
            ?: throw ru.svoemesto.syp.core.db
                .DbException("$what: у эпизода не оказалось ни одной строки")

    companion object {
        /** Имя таблицы лиц. */
        const val TABLE: String = "tbl_faces"

        /** Записываемые столбцы лица в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_episode",
                "frame_number",
                "face_index",
                "x1",
                "y1",
                "x2",
                "y2",
                "shot_id",
                "person_id",
                "origin",
                "is_example",
                "detect_confidence",
            )

        /** Выборка одного лица по идентификатору. */
        val SELECT_BY_ID: String = "SELECT ${Face.READ_COLUMNS} FROM $TABLE WHERE id = ?"

        /** Начало общей выборки лиц. */
        val SELECT_ALL: String = "SELECT ${Face.READ_COLUMNS} FROM $TABLE"
    }
}
