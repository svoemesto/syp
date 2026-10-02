package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SourceParameters
import ru.svoemesto.syp.admin.catalog.SourceProbe
import ru.svoemesto.syp.core.media.ExternalProgram
import ru.svoemesto.syp.core.media.FrameChannel
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Замер параллелизма очереди на настоящей серии (задача T077, М-05).
 *
 * **Что меряется.** CPU-фаза задания `FACES`: декодирование серии целиком,
 * передача кадров полного разрешения в детектор и вызов детектора на каждом
 * кадре. Детектор в этой сборке — заглушка, то есть нагрузка детектора в
 * замере **нулевая**: измеряется именно канал и декодирование, как их
 * видно в задании.
 *
 * **Что замер НЕ даёт.** Полное время задания `FACES` с настоящим детектором:
 * детектор здесь заглушка, нагрузки детекции в замере нет. Критерий SC-001
 * «полная серия укладывается в смену» поэтому **не подтверждается** —
 * подтверждается только CPU-часть, а вывод по ней делается отдельно и с
 * оговорками в отчёте `measurements/m-05-queue-parallelism.md`. Собственных
 * вычислений на видеокарте замер не создаёт ни одного, и это проверяется
 * отдельно выборкой `nvidia-smi`.
 *
 * Проверка пропускается, если не задана переменная `SYP_TEST_SOURCE` с
 * путём к настоящему файлу серии: прогон без файла архива не может дать ни
 * одного числа, а выдуманное число в отчёте хуже пропуска.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FacesParallelismTest {
    private companion object {
        /** Имя переменной окружения с путём к настоящему файлу серии. */
        const val ENV_SOURCE: String = "SYP_TEST_SOURCE"

        /** Имя переменной окружения с путём к программе-декодеру. */
        const val ENV_FFMPEG: String = "SYP_TEST_FFMPEG"

        /** Имя переменной окружения со значениями параллелизма через запятую. */
        const val ENV_PARALLELISM: String = "SYP_TEST_PARALLELISM"

        /** Значения параллелизма по умолчанию: одно задание, два, четыре. */
        const val DEFAULT_PARALLELISM: String = "1,2,4"

        /** Сколько ждать завершения одного замера. */
        const val TIMEOUT_MINUTES: Long = 60
    }

    @Test
    fun `параллелизм CPU-фазы на настоящей серии`() {
        val source = System.getenv(ENV_SOURCE)
        org.junit.jupiter.api.Assumptions.assumeTrue(!source.isNullOrBlank()) {
            "переменная $ENV_SOURCE не задана: замер параллелизма на настоящей серии " +
                "не выполнялся. Проверка не заменяет собой замер"
        }
        val ffmpeg = System.getenv(ENV_FFMPEG)?.takeIf { it.isNotBlank() } ?: "ffmpeg"
        val probed = SourceProbe(ExternalProgram(), "ffprobe").probe(Path.of(source))
        val series = seriesOf(probed, source)
        val levels = parallelLevels()

        println("ЗАМЕР ПАРАЛЛЕЛИЗМА ОЧЕРЕДИ (М-05)")
        println("файл: $source")
        println("кадров: ${series.frameCount}, разрешение: ${series.width}x${series.height}")
        println("детектор: ${StubFaceDetector.KEY} (заглушка, нагрузка нулевая)")
        println("декодер: $ffmpeg")
        println("уровни параллелизма: ${levels.joinToString(", ")}")

        levels.forEach { level ->
            val measurement = measure(ffmpeg, series, level)
            println(
                "ИТОГ параллелизм=$level всего_кадров=${measurement.frames} " +
                    "общее_время_с=${measurement.wallSeconds} " +
                    "самый_быстрый_с=${measurement.fastestSeconds} " +
                    "самый_медленный_с=${measurement.slowestSeconds} " +
                    "суммарно_кадров_в_секунду=${measurement.totalFps} " +
                    "медленнейший_проход_кадров_в_секунду=${measurement.slowestFps}",
            )
        }
    }

    /**
     * Прогоняет N проходов по серии одновременно и меряет.
     *
     * @param ffmpeg путь к декодеру
     * @param series серия
     * @param level сколько проходов одновременно
     * @return результат замера
     */
    private fun measure(
        ffmpeg: String,
        series: Series,
        level: Int,
    ): Measurement {
        val pool =
            Executors.newFixedThreadPool(level) { runnable ->
                Thread(runnable, "syp-faces-measure-$level").apply { isDaemon = true }
            }
        try {
            val tasks: List<Callable<Double>> =
                (0 until level).map { index ->
                    Callable {
                        val started = System.nanoTime()
                        val scan = FaceScan(FrameChannel(ffmpeg), StubFaceDetector())
                        val result = scan.scan(series)
                        assertEquals(
                            series.frameCount,
                            result.frames,
                            "проход обязан обработать все кадры серии: частичный результат " +
                                "не является замером",
                        )
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        println(
                            "ПРОХОД параллелизм=$level номер=$index кадров=${result.frames} " +
                                "секунд=$seconds",
                        )
                        seconds
                    }
                }
            val started = System.nanoTime()
            val futures = pool.invokeAll(tasks, TIMEOUT_MINUTES, TimeUnit.MINUTES)
            futures.forEach { future ->
                assertTrue(
                    !future.isCancelled,
                    "проход не закончился за $TIMEOUT_MINUTES минут: замер не состоялся",
                )
            }
            val seconds = futures.map { it.get() }
            val wall = (System.nanoTime() - started) / 1_000_000_000.0
            val frames = series.frameCount.toLong() * level
            val slowest = seconds.max()
            return Measurement(
                frames = frames,
                wallSeconds = "%.1f".format(wall),
                fastestSeconds = "%.1f".format(seconds.min()),
                slowestSeconds = "%.1f".format(slowest),
                totalFps = "%.1f".format(frames / wall),
                slowestFps = "%.1f".format(series.frameCount / slowest),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Строит серию по опрошенному файлу.
     *
     * @param probed параметры файла
     * @param source путь к файлу
     * @return серия
     */
    private fun seriesOf(
        probed: SourceParameters,
        source: String,
    ): Series =
        Series(
            serialId = 1,
            ordinal = 0,
            name = Path.of(source).fileName.toString(),
            sourcePath = source,
            byteSize = probed.byteSize,
            fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
            frameCount = probed.frameCount,
            timeBaseNum = probed.timeBaseNum,
            timeBaseDen = probed.timeBaseDen,
            width = probed.width,
            height = probed.height,
            durationNum = probed.durationNum,
            durationDen = probed.durationDen,
            videoCodec = probed.videoCodec,
            videoProfile = probed.videoProfile,
            pixelFormat = probed.pixelFormat,
            keyframeMap = KeyframeMap.build(probed.frameCount, listOf(0)),
        )

    /**
     * Читает уровни параллелизма из окружения.
     *
     * @return уровни по возрастанию
     */
    private fun parallelLevels(): List<Int> =
        System
            .getenv(ENV_PARALLELISM)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PARALLELISM.split(",").map { it.trim().toInt() }

    /**
     * Числа одного замера.
     *
     * @property frames сколько кадров обработано всеми проходами
     * @property wallSeconds время от старта до конца всех проходов
     * @property fastestSeconds время самого быстрого прохода
     * @property slowestSeconds время самого медленного прохода
     * @property totalFps суммарная скорость всех проходов, кадров в секунду
     * @property slowestFps скорость самого медленного прохода, кадров в секунду
     */
    private data class Measurement(
        val frames: Long,
        val wallSeconds: String,
        val fastestSeconds: String,
        val slowestSeconds: String,
        val totalFps: String,
        val slowestFps: String,
    )
}
