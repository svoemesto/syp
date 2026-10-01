package ru.svoemesto.syp.admin.integrity

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.SerialStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesRegistration
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.catalog.SourceProbe
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.jobs.AdminJobWorker
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.media.ExternalProgram
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сверка посчитанной суммы с внешней (задача T045, замер М-11).
 *
 * Что проверяется. Сумма, посчитанная системой, сверяется с выводом
 * `sha256sum` — программы, которой воспользуется воркер на машине
 * пользователя. Сверка идёт на **настоящем файле серии**: сумма на
 * синтетическом файле ничего не говорит о 5,6 ГБ, которые придётся читать.
 *
 * Проверка идёт через настоящий путь: постановка задания, воркер, исполнитель,
 * запись в справочник. Обход воркера ничего бы не доказал — сумма, посчитанная
 * не тем путём, каким она считается в работе, может отличаться от правильной.
 *
 * Замер ведётся в трёх прогонах: время чтения 5,6 ГБ меняется от кэша
 * диска, а число без повторов — мнение, а не измерение.
 *
 * Проверка требует файла серии и живой базы. Без них она **пропускается**,
 * а не падает.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChecksumParityTest {
    private lateinit var db: Db
    private lateinit var queue: JobQueue
    private lateinit var seriesStore: SeriesStore
    private lateinit var registry: ChecksumRegistry
    private lateinit var enqueuer: ChecksumEnqueuer
    private lateinit var worker: AdminJobWorker
    private lateinit var probe: SourceProbe

    /**
     * Поднимает доступ к базе, очередь и исполнителя подсчёта.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        queue = JobQueue(db)
        seriesStore = SeriesStore(db)
        registry = ChecksumRegistry(db)
        enqueuer = ChecksumEnqueuer(queue, seriesStore, registry)
        probe = SourceProbe(ExternalProgram(), requireProgram("ffprobe"))
        val storageRoot = Files.createTempDirectory("syp-parity-storage")
        worker =
            AdminJobWorker(
                queue = queue,
                handlers = mapOf<JobKind, JobHandler>(JobKind.HASH to HashJob(seriesStore, registry)),
                artifactRegistry = ArtifactRegistry(db, FileSystemStorage(storageRoot)),
                db = db,
                concurrency = 1,
                gpuConcurrency = 1,
            )
    }

    /**
     * Путь к проверяемому файлу серии из окружения.
     *
     * @return путь к файлу серии
     * @throws org.opentest4j.TestAbortedException если переменная не задана
     */
    private fun requireSeries(): Path {
        val declared = System.getenv(ENV_SERIES)
        assumeTrue(!declared.isNullOrBlank()) {
            "Переменная $ENV_SERIES не задана: сверка суммы с внешней пропущена"
        }
        val path = Paths.get(declared!!)
        assumeTrue(Files.isRegularFile(path)) { "Файла серии $path нет: сверка пропущена" }
        return path
    }

    /**
     * Полный путь к программе в `PATH`.
     *
     * @param name имя программы
     * @return полный путь
     * @throws org.opentest4j.TestAbortedException если программа не найдена
     */
    private fun requireProgram(name: String): String {
        val found =
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.isNotBlank() }
                .map { Paths.get(it, name) }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()
        assumeTrue(found != null) { "Программа $name не найдена в PATH: сверка пропущена" }
        return found!!
    }

    /**
     * Считает сумму файла внешней программой `sha256sum`.
     *
     * Именно этой программой воспользуется воркер на машине пользователя,
     * поэтому сверка идёт с ней, а не со вторым вызовом того же кода, что и
     * в системе: два вызова одного алгоритма ошибаются одинаково.
     *
     * @param file путь к файлу
     * @return сумма файла
     */
    private fun sha256sumOf(file: Path): String {
        val result =
            ExternalProgram()
                .runOrFail(
                    requireProgram("sha256sum"),
                    listOf(file.toString()),
                    environment = mapOf("LC_ALL" to "C"),
                )
        return result.output
            .trim()
            .split(" ")
            .first()
    }

    @Test
    fun `сумма системы совпадает с sha256sum файла`() {
        val seriesPath = requireSeries()
        val serials = SerialStore(db)
        val registration = SeriesRegistration(serials, seriesStore, probe)

        println("=== СВЕРКА СУММЫ С ВНЕШНЕЙ: ${seriesPath.fileName} ===")
        val probeStarted = System.nanoTime()
        val series: Series =
            registration.register(
                serials.create("Сверка ${System.nanoTime()}", seriesPath.parent.toString()).id!!,
                seriesPath.toString(),
                "S1E1",
            )
        println("определение параметров серии: ${elapsedSeconds(probeStarted)} с")
        println("серия: ${series.frameCount} кадров, ${series.byteSize} байт, карта ключевых ${series.keyframeMap?.keyframeCount()}")

        val external = sha256sumOf(seriesPath)
        println("sha256sum файла: $external")

        val times = mutableListOf<Double>()
        repeat(RUNS) { run ->
            val enqueued = enqueuer.enqueue(series.id!!, "сверка с внешней суммой, прогон ${run + 1}")
            val job =
                queue.claim(JobKindsForHash)
                    ?: throw IllegalStateException("Задание $enqueued не взято воркером: очередь пуста")
            val started = System.nanoTime()
            val done = worker.runJob(job)
            val seconds = elapsedSeconds(started)
            times.add(seconds)
            val current = registry.current(series.id!!)
            println(
                "прогон ${run + 1}: задание " +
                    if (done) {
                        "DONE"
                    } else {
                        "не DONE" +
                            ", ${"%.2f".format(seconds)} с, " +
                            "${"%.1f".format(series.byteSize / seconds / 1_048_576.0)} МБ/с, сумма ${current?.digest}"
                    },
            )
            assertTrue(done, "задание подсчёта обязано завершиться успешно")
            assertEquals(external, current?.digest, "сумма системы разошлась с sha256sum на прогоне ${run + 1}")
        }

        println("--- сводка ---")
        println("прогонов: $RUNS")
        println("время чтения и подсчёта, с: ${times.joinToString(", ") { "%.2f".format(it) }}")
        println("минимальное: ${"%.2f".format(times.min())} с, максимальное: ${"%.2f".format(times.max())} с")
        val average = times.average()
        println("среднее: ${"%.2f".format(average)} с, скорость ${"%.1f".format(series.byteSize / average / 1_048_576.0)} МБ/с")
    }

    /**
     * Секунды от момента запуска.
     *
     * @param started момент запуска в наносекундах
     * @return прошедшие секунды с дробной частью
     */
    private fun elapsedSeconds(started: Long): Double = (System.nanoTime() - started) / 1_000_000_000.0

    private companion object {
        /** Имя переменной окружения с путём к файлу серии. */
        const val ENV_SERIES: String = "SYP_SOURCE_SERIES"

        /** Сколько прогонов измеряется: один прогон — мнение, три — измерение. */
        const val RUNS: Int = 3

        /** Виды заданий, которые берёт воркер в этой проверке. */
        val JobKindsForHash: List<JobKind> = listOf(JobKind.HASH)
    }
}
