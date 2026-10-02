package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.MovieSetting
import ru.svoemesto.syp.admin.catalog.MovieSettings
import ru.svoemesto.syp.core.jobs.JobProgress
import ru.svoemesto.syp.core.jobs.ParamsHash
import ru.svoemesto.syp.core.media.ExternalProgram
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Результат детекции границ.
 *
 * Содержит **номера кадров**, а не время: номер кадра — единственный источник
 * правды для границ, а время из отметок детектора обратно переводится в
 * кадры точной арифметикой (ADR-0001).
 *
 * @property sceneBoundaries номера кадров, где начинаются сцены, по возрастанию
 * @property shotBoundaries номера кадров, где начинаются планы, по возрастанию
 * @property scores число оценок, полученных детектором
 * @property algorithmVersion версия алгоритма, которой получен результат
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class DetectionResult(
    val sceneBoundaries: List<Int>,
    val shotBoundaries: List<Int>,
    val scores: Int,
    val algorithmVersion: String = ALGORITHM_VERSION,
) {
    init {
        require(sceneBoundaries.sorted() == sceneBoundaries) { "Номера кадров границ сцен не отсортированы" }
        require(shotBoundaries.sorted() == shotBoundaries) { "Номера кадров границ планов не отсортированы" }
        // Каждая граница сцены обязана быть и границей плана: порог сцены не
        // ниже порога плана, поэтому любая оценка, признанная границей сцены,
        // признана и границей плана. Проверка ловит ошибку в порогах в момент
        // появления, а не при первом же разборе структуры.
        val shots = shotBoundaries.toSet()
        val orphan = sceneBoundaries.filter { it !in shots }
        require(orphan.isEmpty()) {
            "Границы сцен $orphan не попали в границы планов: порог сцены ниже порога плана, " +
                "а так быть не должно — сцена начинается планом"
        }
    }

    /** Границы сцен вместе с началом эпизода: сцена начинается с первого кадра. */
    fun scenesWithStart(frameCount: Int): List<Int> = listOf(0) + sceneBoundaries.filter { it > 0 }

    companion object {
        /** Версия алгоритма детекции. */
        const val ALGORITHM_VERSION: String = "ffmpeg-scdet-1"
    }
}

/**
 * Детектор границ сцен и планов.
 *
 * **Один проход — два порога.** Встроенный детектор ffmpeg (`scdet`) выдаёт
 * оценку смены сцены для каждого кадра. Проход запускается с порогом,
 * равным меньшему из двух, и разбирается целиком: сцены отбираются по
 * высокому порогу, планы — по низкому. Именно так оба порога остаются
 * параметрами задания, а план не может выйти за пределы сцены: он отобран из
 * того же потока оценок, а его границы — подмножество границ сцен
 * (ADR-0005, research.md Т-05).
 *
 * Второй проход с другим порогом был бы дороже и дал бы то же самое: оценки
 * кадров не зависят от порога, порог влияет только на отбор.
 *
 * Замер на реальном эпизоде 1080p: декодирование эпизода — 157 с, детекция
 * границ — 162 с, то есть встроенный детектор стоит почти ноль
 * дополнительного прохода (ADR-0005).
 *
 * @property program единая точка запуска внешних программ
 * @property ffmpegPath путь к программе `ffmpeg`; приходит из конфигурации
 *   развёртывания, а не из данных задания (ADR-0010)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SceneDetector(
    private val program: ExternalProgram,
    private val ffmpegPath: String,
) {
    /**
     * Находит границы сцен и планов одним проходом.
     *
     * @param episode эпизод, файл которой разбирается
     * @param sceneThreshold порог границы сцены: оценка не ниже него считается
     *   границей сцены
     * @param shotThreshold порог границы плана: оценка не ниже него считается
     *   границей плана; обязан быть не выше порога сцены
     * @param progress приёмник прогресса из потока программы
     * @return границы сцен и планов
     * @throws IllegalArgumentException если пороги заданы неверно или порог
     *   плана выше порога сцены: тогда граница плана могла бы оказаться вне
     *   сцены
     * @throws ru.svoemesto.syp.core.media.ExternalProgramFailed если программа
     *   завершилась с ненулевым кодом
     */
    fun detect(
        episode: Episode,
        sceneThreshold: Double,
        shotThreshold: Double,
        progress: (JobProgress) -> Unit = {},
    ): DetectionResult {
        require(sceneThreshold > 0.0 && shotThreshold > 0.0) {
            "Пороги границ должны быть положительными, задано $shotThreshold и $sceneThreshold"
        }
        require(shotThreshold <= sceneThreshold) {
            "Порог границы плана ($shotThreshold) выше порога границы сцены ($sceneThreshold): " +
                "граница плана тогда может оказаться вне сцены. Пороги задаются настройками фильма"
        }

        val output =
            program.run(
                executablePath = ffmpegPath,
                arguments =
                    listOf(
                        "-hide_banner",
                        "-nostdin",
                        "-i",
                        episode.sourcePath,
                        "-vf",
                        "scdet=threshold=" + minOf(sceneThreshold, shotThreshold),
                        "-f",
                        "null",
                        "-",
                    ),
                progressReader = JobProgress::parseFfmpegProgress,
                onProgress = progress,
            )
        if (!output.isSuccess) {
            throw ru.svoemesto.syp.core.media
                .ExternalProgramFailed(output, PROGRAM_NAME)
        }

        val scores = parseScores(output.output, episode)
        return DetectionResult(
            sceneBoundaries = scores.filter { it.score >= sceneThreshold }.map { it.frame }.sorted(),
            shotBoundaries = scores.filter { it.score >= shotThreshold }.map { it.frame }.sorted(),
            scores = scores.size,
        )
    }

    /**
     * Хеш параметров детекции.
     *
     * В хеш входят оба порога и версия алгоритма: результат, полученный при
     * других входах, нельзя выдавать за тот же самый (FR-090, Р-10).
     *
     * @param sceneThreshold порог границы сцены
     * @param shotThreshold порог границы плана
     * @return 64 шестнадцатеричных символов в нижнем регистре
     */
    fun paramsHash(
        sceneThreshold: Double,
        shotThreshold: Double,
    ): String = paramsHashOf(sceneThreshold, shotThreshold)

    /**
     * Разбирает оценки детектора из объединённого вывода программы.
     *
     * Разбираются обе части строки: оценка и отметка времени. Отметка
     * переводится в номер кадра точной арифметикой — округление по
     * `Double` на длинном эпизоде давало бы расхождение в десятки кадров.
     *
     * @param output объединённый вывод программы
     * @param episode эпизод, для которой сняты параметры времени
     * @return оценки с номерами кадров по возрастанию
     */
    private fun parseScores(
        output: String,
        episode: Episode,
    ): List<Score> {
        val frameDuration = BigDecimal(episode.timeBaseNum).divide(BigDecimal(episode.timeBaseDen), 12, RoundingMode.HALF_UP)
        val scores = mutableListOf<Score>()
        output.lineSequence().forEach { line ->
            val score = SCORE.find(line) ?: return@forEach
            val seconds = BigDecimal(score.groupValues[2])
            val frame =
                seconds
                    .divide(frameDuration, 0, RoundingMode.HALF_UP)
                    .toInt()
            if (frame < 0 || frame >= episode.frameCount) {
                throw IllegalArgumentException(
                    "Отметка границы ${score.groupValues[2]} с сохранённой оценкой " +
                        "${score.groupValues[1]} сходится на кадр $frame, а эпизод содержит " +
                        "${episode.frameCount} кадров: число кадров и частокадровая база не согласуются",
                )
            }
            scores.add(Score(frame, BigDecimal(score.groupValues[1]).toDouble()))
        }
        return scores.sortedBy { it.frame }
    }

    /**
     * Оценка смены сцены для кадра.
     *
     * @property frame номер кадра с нуля
     * @property score оценка детектора
     */
    private data class Score(
        val frame: Int,
        val score: Double,
    )

    companion object {
        /** Имя программы для текста ошибки. */
        const val PROGRAM_NAME: String = "ffmpeg"

        /**
         * Хеш входов детекции по паре порогов.
         *
         * Единственное место, где хеш считается: и задание, и слой устаревания
         * берут его отсюда, иначе «актуальный хеш» и «хеш прогона»
         * разошлись бы и каждый результат вечно считался бы устаревшим.
         *
         * @param sceneThreshold порог границы сцены
         * @param shotThreshold порог границы плана
         * @return 64 шестнадцатеричных символов в нижнем регистре
         */
        fun paramsHashOf(
            sceneThreshold: Double,
            shotThreshold: Double,
        ): String = ParamsHash.of(DetectionResult.ALGORITHM_VERSION, sceneThreshold, shotThreshold)

        /**
         * Хеш входов детекции по настройкам фильма.
         *
         * @param settings настройки фильма
         * @return 64 шестнадцатеричных символов в нижнем регистре
         */
        fun paramsHashOf(settings: MovieSettings): String =
            paramsHashOf(
                settings.number(MovieSetting.SCENE_THRESHOLD),
                settings.number(MovieSetting.SHOT_THRESHOLD),
            )

        /** Шаблон строки оценки: `lavfi.scd.score: 12.345, lavfi.scd.time: 5.96`. */
        private val SCORE: Regex = Regex("""lavfi\.scd\.score:\s*([0-9.]+)\s*,\s*lavfi\.scd\.time:\s*([0-9.]+)""")
    }
}
