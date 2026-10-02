package ru.svoemesto.syp.core.recipe

import ru.svoemesto.syp.core.signing.CanonicalObject
import ru.svoemesto.syp.core.signing.Canonicalizer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Один фрагмент сценария сборки в том виде, в каком он попадает в файл.
 *
 * Фрагмент **самодостаточен**: кроме границ и пути к файлу эпизода он несёт
 * снимок данных сцены — название, место действия и имена персонажей. Снимком,
 * а не ссылкой: правка справочника после выдачи не должна «слепить» уже
 * скачанный сценарий, а локальный показ работает без сервера (FR-089d).
 *
 * **Обе пары границ обязательны.** Расчётные [firstFrame] и [lastFrame] взяты
 * из разметки, фактические [cutFirstFrame] и [cutLastFrame] — округлены до
 * ключевых кадров эпизода. Пользователь видит округление заранее, а не после
 * работы (ADR-0006, FR-082).
 *
 * @property ordinal порядковый номер фрагмента в сценарии, с единицы
 * @property sceneId сцена-источник фрагмента
 * @property sceneTitle название сцены-снимок; `null` — у сцены названия нет
 * @property location место действия-снимок; `null` — место не назначено
 * @property persons имена персонажей-снимок в алфавитном порядке
 * @property episodeId эпизод-источник фрагмента
 * @property episodeName название эпизода
 * @property relativePath путь к файлу эпизода **относительно корня фильма**
 * @property sourceSha256 снимок эталонной суммы файла эпизода на момент выдачи
 * @property firstFrame расчётная граница начала по размеченному плану
 * @property lastFrame расчётная граница конца по размеченному плану
 * @property cutFirstFrame фактическая граница начала: не позже расчётной
 * @property cutLastFrame фактическая граница конца: не раньше расчётной
 * @throws IllegalArgumentException если нарушен инвариант фрагмента
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeItemDocument(
    val ordinal: Int,
    val sceneId: Long,
    val sceneTitle: String?,
    val location: String?,
    val persons: List<String>,
    val episodeId: Long,
    val episodeName: String,
    val relativePath: String,
    val sourceSha256: String,
    val firstFrame: Int,
    val lastFrame: Int,
    val cutFirstFrame: Int,
    val cutLastFrame: Int,
) {
    init {
        require(ordinal > 0) { "Порядковый номер фрагмента должен начинаться с единицы, задано $ordinal" }
        require(episodeName.isNotBlank()) { "Название эпизода фрагмента обязательно" }
        require(sourceSha256.matches(HEX_64)) {
            "Сумма источника «$sourceSha256» не является SHA-256 в виде 64 " +
                "шестнадцатеричных символов в нижнем регистре (FR-089)"
        }
        require(firstFrame >= 0 && lastFrame >= firstFrame) {
            "Расчётные границы фрагмента заданы неверно: $firstFrame…$lastFrame"
        }
        require(cutFirstFrame <= firstFrame && cutLastFrame >= lastFrame) {
            "Фактические границы фрагмента обязаны охватывать расчётные: " +
                "получено $cutFirstFrame…$cutLastFrame при расчётных $firstFrame…$lastFrame. " +
                "Обратное направление округления запрещено (ADR-0006, FR-082)"
        }
        RecipePaths.requireInsideMovieTree(relativePath)
    }

    /** Число кадров фрагмента по **фактическим** границам. */
    val cutFrameCount: Int
        get() = cutLastFrame - cutFirstFrame

    /** Строит объект канонической формы фрагмента. */
    fun toCanonicalObject(): CanonicalObject =
        CanonicalObject(
            buildList {
                add("ordinal" to ordinal)
                add("sceneId" to sceneId)
                // Отсутствующее необязательное значение не пишется вовсе, а не
                // пишется как `null` (контракт рецепта, правило 5 раздела 2.1).
                if (sceneTitle != null) add("sceneTitle" to sceneTitle)
                if (location != null) add("tbl_locations" to location)
                if (persons.isNotEmpty()) add("persons" to persons)
                add("episodeId" to episodeId)
                add("episodeName" to episodeName)
                add("relativePath" to relativePath)
                add("sourceSha256" to sourceSha256)
                add("firstFrame" to firstFrame)
                add("lastFrame" to lastFrame)
                add("cutFirstFrame" to cutFirstFrame)
                add("cutLastFrame" to cutLastFrame)
            },
        )

    private companion object {
        /** Признак SHA-256 в виде 64 шестнадцатеричных символов в нижнем регистре. */
        val HEX_64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Сценарий сборки — исполняемый рецепт для машины пользователя.
 *
 * Это **данные, а не программа**: воркер разбирает файл как JSON и выполняет
 * собственную логику (контракт рецепта, раздел 2.3). Видеофайла здесь нет и
 * не появится: сервер формирует рецепт, а собирает подборку машина
 * пользователя (ADR-0009, FR-088).
 *
 * Поля [expectedDurationMs] и [expectedFrameCount] — **расчёт по сумме
 * фактических** границ фрагментов. Фактические величины измеряет воркер и
 * кладёт в отчёт о выполнении: подменять расчёт измеренным на сервере нельзя
 * (FR-083).
 *
 * @property schemaVersion версия формата; воркер отвергает незнакомую версию
 * @property movieId фильм-владелец
 * @property movieName название фильма
 * @property recipeId идентификатор сценария в базе
 * @property recipeName название сценария
 * @property signingKeyId идентификатор пары ключей, которой подписан файл
 * @property createdAt момент выдачи; в файл попадает с точностью до секунд
 * @property rootLayout как устроены пути; см. [RecipeFormat.ROOT_LAYOUT_MOVIE_TREE]
 * @property audioTrackCount число аудиодорожек в готовом файле
 * @property expectedDurationMs расчётная длительность подборки в миллисекундах
 * @property expectedFrameCount расчётное число кадров подборки
 * @property items фрагменты в порядке следования
 * @throws IllegalArgumentException если нарушен инвариант сценария
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeDocument(
    val schemaVersion: Int = RecipeFormat.SCHEMA_VERSION,
    val movieId: Long,
    val movieName: String,
    val recipeId: Long,
    val recipeName: String,
    val signingKeyId: String,
    val createdAt: OffsetDateTime,
    val rootLayout: String = RecipeFormat.ROOT_LAYOUT_MOVIE_TREE,
    val audioTrackCount: Int,
    val expectedDurationMs: Long,
    val expectedFrameCount: Long,
    val items: List<RecipeItemDocument>,
) {
    init {
        require(movieName.isNotBlank()) { "Название фильма обязательно" }
        require(recipeName.isNotBlank()) { "Название сценария обязательно" }
        require(signingKeyId.isNotBlank()) {
            "Идентификатор ключа подписи обязателен: без него сценарий нельзя " +
                "проверить на машине пользователя (FR-089c)"
        }
        require(items.isNotEmpty()) { "Сценарий без фрагментов не выдаётся" }
        require(audioTrackCount > 0) { "Число аудиодорожек должно быть положительным, задано $audioTrackCount" }
        require(expectedDurationMs >= 0 && expectedFrameCount >= 0) {
            "Расчётные величины не могут быть отрицательными: " +
                "длительность $expectedDurationMs, кадров $expectedFrameCount"
        }
        items.forEachIndexed { index, item ->
            require(item.ordinal == index + 1) {
                "Порядковые номера фрагментов должны идти подряд с единицы: " +
                    "на позиции ${index + 1} номер ${item.ordinal}"
            }
        }
    }

    /** Строит объект канонической формы сценария. */
    fun toCanonicalObject(): CanonicalObject =
        CanonicalObject(
            listOf(
                "schemaVersion" to schemaVersion,
                "movieId" to movieId,
                "movieName" to movieName,
                "recipeId" to recipeId,
                "recipeName" to recipeName,
                // Идентификатор ключа входит в подписываемый файл: по нему
                // воркер выбирает ключ проверки. Подпись при этом хранится
                // вне файла — иначе пришлось бы подписывать «файл с местом
                // для подписи» (контракт рецепта, раздел 3).
                "signingKeyId" to signingKeyId,
                "createdAt" to RecipeFormat.timestamp(createdAt),
                "rootLayout" to rootLayout,
                "audioTrackCount" to audioTrackCount,
                "expectedDurationMs" to expectedDurationMs,
                "expectedFrameCount" to expectedFrameCount,
                "items" to items.map { it.toCanonicalObject() },
            ),
        )
}

/**
 * Формат сценария сборки.
 *
 * Формат — **часть контракта**: по нему воркер на машине пользователя
 * разбирает файл, и подпись ставится над каноническими байтами именно этой
 * формы (контракт рецепта, разделы 2 и 3).
 *
 * Правила канонизации, все обязательные:
 *
 * 1. порядок полей фиксирован объявлением и совпадает с порядком полей
 *    контракта, раздел 2.2;
 * 2. перевод строки — только `LF`, кодировка UTF-8 без BOM;
 * 3. числа целые, без дробной части и ведущих нулей;
 * 4. необязательное отсутствующее значение **не пишется вовсе**, а не пишется
 *    как `null`;
 * 5. строковые значения экранируются по правилам JSON, поэтому файл всегда
 *    разбирается, даже если название сцены содержит кавычку.
 *
 * Проверяемое свойство: одни и те же данные, выданные дважды, дают побайтово
 * одинаковые байты. Без этого проверка подписи была бы лотереей
 * (research.md Т-23).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object RecipeFormat {
    /** Версия формата, которую понимает этот код. */
    const val SCHEMA_VERSION: Int = 1

    /**
     * Раскладка путей: пути в сценарии относительны корню фильма.
     *
     * У пользователя своя копия дерева фильма под своим корнем, поэтому
     * абсолютный путь с машины администратора в сценарий не попадает
     * (FR-089a).
     */
    const val ROOT_LAYOUT_MOVIE_TREE: String = "MOVIE_TREE"

    /** Тип содержимого файла сценария при выдаче. */
    const val CONTENT_TYPE: String = "application/json"

    /** Суффикс имени файла сценария. */
    const val FILE_SUFFIX: String = ".syp-recipe.json"

    /**
     * Канонические байты сценария.
     *
     * Именно эти байты подписываются и именно их получает пользователь:
     * подписывать объект и отдавать файл было бы разными вещами, и проверка
     * подписи на машине пользователя ловила бы не то расхождение, которое
     * хочет поймать (FR-089c).
     *
     * @param document сценарий
     * @return канонические байты в UTF-8 без BOM
     */
    fun canonicalBytes(document: RecipeDocument): ByteArray = Canonicalizer.canonicalBytes(document.toCanonicalObject())

    /**
     * Записывает момент выдачи в виде, принятом в формате.
     *
     * Момент округляется до секунд и приводится к UTC: иначе две выдачи одних
     * и тех же данных в разных поясах дали бы разные байты, а подпись перестала
     * бы быть воспроизводимой.
     *
     * @param value момент выдачи
     * @return текст вида `2026-10-02T12:41:07Z`
     */
    fun timestamp(value: OffsetDateTime): String =
        value
            .withOffsetSameInstant(ZoneOffset.UTC)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
}
