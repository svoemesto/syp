package ru.svoemesto.syp.core.recipe

/**
 * Параметры эпизода, по которым решается, можно ли собрать подборку без
 * перекодирования.
 *
 * Это **снимок того, что снято с файла при регистрации эпизода**, а не повторный
 * опрос: дополнительные проходы по видео не нужны, и отказ приходит сразу, а
 * не через полчаса ожидания на машине пользователя (FR-087).
 *
 * Ни одна величина здесь не вводится оператором: она снята с файла и
 * принадлежит эпизоду, а не подборке.
 *
 * @property episodeId идентификатор эпизода
 * @property name название эпизода
 * @property width ширина кадра в пикселях
 * @property height высота кадра в пикселях
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео; пустая строка — профиль не задан
 * @property pixelFormat формат пикселей
 * @property timeBaseNum числитель частокадровой базы
 * @property timeBaseDen знаменатель частокадровой базы
 * @property audioCodec кодек аудио; `null` у эпизода без звука
 * @property audioChannels число аудиоканалов; `null` у эпизода без звука
 * @property audioSampleRate частота дискретизации; `null` у эпизода без звука
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class EpisodeParameters(
    val episodeId: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val videoCodec: String,
    val videoProfile: String?,
    val pixelFormat: String,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
) {
    init {
        require(width > 0 && height > 0) { "Разрешение эпизода «$name» должно быть положительным" }
        require(videoCodec.isNotBlank()) { "Кодек видео эпизода «$name» обязателен" }
        require(timeBaseNum > 0 && timeBaseDen > 0) {
            "Частокадровый база эпизода «$name» должна быть положительной"
        }
    }

    /** Длительность кадра в миллисекундах: единственный источник расчёта времени. */
    val frameDurationMs: Long
        get() = timeBaseNum.toLong() * 1000L / timeBaseDen
}

/**
 * Один эпизод в отчёте о несовместимости.
 *
 * В ответе перечисляются эпизоды **и те признаки, по которым они различаются**:
 * «сценарий не выдан» без указания причины бесполезно, а молча ухудшить
 * качество нельзя (FR-087, FR-092).
 *
 * @property parameters параметры эпизода
 * @property differingAttributes имена признаков, по которым эпизод отличается
 *   от опорной
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class IncompatibleEpisode(
    val parameters: EpisodeParameters,
    val differingAttributes: List<String>,
)

/**
 * Результат проверки совместимости эпизодов подборки.
 *
 * @property reference опорный эпизод, с которой сравнивались остальные
 * @property incompatible эпизоды, не совпадающие с опорной
 * @property differingAttributes все признаки, по которым разошлись эпизода
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CompatibilityReport(
    val reference: EpisodeParameters,
    val incompatible: List<IncompatibleEpisode>,
) {
    /** Все эпизоды совпадают: подборку можно выдавать. */
    val isCompatible: Boolean
        get() = incompatible.isEmpty()

    /** Все признаки, по которым разошлись эпизода, без повторов и по алфавиту. */
    val differingAttributes: List<String>
        get() = incompatible.flatMap { it.differingAttributes }.distinct().sorted()
}

/**
 * Проверка совместимости эпизодов **до** выдачи сценария.
 *
 * Сборка идёт без перекодирования (ADR-0006), поэтому файлы разных эпизодов
 * склеиваются потоковым копированием — и склеиваются только если совпадают
 * параметры, по которым ffmpeg различает потоки. Подборка из разных эпизодов
 * фильма **разрешена** (FR-086): запрет не на различие эпизодов, а на различие
 * параметров склейки.
 *
 * Проверка идёт по сохранённым параметрам эпизода, а не по повторному опросу
 * файлов: лишнего прохода по видео нет, отказ приходит мгновенно, и до него
 * пользователь не успевает потратить время (FR-087).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object RecipeCompatibility {
    /**
     * Сравнивает эпизоды подборки.
     *
     * Опорный считается первый эпизод списка: порядок определяется выдачей, а не
     * сортировкой, иначе отчёт зависел бы от того, как отсортировали эпизода.
     *
     * @param episode эпизода подборки
     * @return отчёт; при пустом списке — отказ, а не «всё совместимо»
     * @throws IllegalArgumentException если список эпизодов пуст
     */
    fun check(episode: List<EpisodeParameters>): CompatibilityReport {
        require(episode.isNotEmpty()) {
            "Проверять совместимость нечего: список эпизодов подборки пуст"
        }
        val reference = episode.first()
        val incompatible =
            episode
                .drop(1)
                .map { candidate -> IncompatibleEpisode(candidate, differences(reference, candidate)) }
                .filter { it.differingAttributes.isNotEmpty() }
        return CompatibilityReport(reference, incompatible)
    }

    /**
     * Перечисляет признаки, по которым эпизода различаются.
     *
     * Порядок признаков зафиксирован объявлением: он попадает в ответ и
     * должен быть одинаковым от запуска к запуску.
     *
     * @param reference опорный эпизод
     * @param candidate сравниваемый эпизод
     * @return имена различающихся признаков
     */
    fun differences(
        reference: EpisodeParameters,
        candidate: EpisodeParameters,
    ): List<String> =
        buildList {
            if (reference.width != candidate.width) add("width")
            if (reference.height != candidate.height) add("height")
            if (reference.videoCodec != candidate.videoCodec) add("videoCodec")
            if (reference.videoProfile.orEmpty() != candidate.videoProfile.orEmpty()) add("videoProfile")
            if (reference.pixelFormat != candidate.pixelFormat) add("pixelFormat")
            if (reference.timeBaseNum != candidate.timeBaseNum || reference.timeBaseDen != candidate.timeBaseDen) {
                add("frameRateBase")
            }
            if (reference.audioCodec != candidate.audioCodec) add("audioCodec")
            if (reference.audioChannels != candidate.audioChannels) add("audioChannels")
            if (reference.audioSampleRate != candidate.audioSampleRate) add("audioSampleRate")
        }
}
