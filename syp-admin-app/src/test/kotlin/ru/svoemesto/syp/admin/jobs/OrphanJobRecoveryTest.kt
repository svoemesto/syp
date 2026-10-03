package ru.svoemesto.syp.admin.jobs

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Проверка того, что задание, оставшееся в работе без исполнителя,
 * возвращается в очередь, а не висит.
 *
 * Сценарий стенды: контейнер пересоздали жёстко, задание детекции лиц
 * осталось в состоянии `WORKING`, исполнитель умер вместе с контейнером, и
 * состояние задания продолжало утверждать, что работа идёт — полтора часа.
 *
 * Проверка повторяет это: задание переводят в работу вручную, как будто
 * воркер был убит, и поднимают воркер заново.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrphanJobRecoveryTest {
    /** Очередь, в которой живут задания теста. */
    private lateinit var queue: JobQueue

    /** Сколько ждать смены состояния перед выводом провала, мс. */
    private val stateWaitMillis = 10_000L

    /** Пауза между попытками увидеть смену состояния, мс. */
    private val statePollMillis = 100L

    /** Подготовка очереди перед набором. */
    @BeforeAll
    fun setUp() {
        queue = JobQueue(TestDatabase.assumeDatabase())
        TestDatabase.assumeDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tbl_jobs")
            }
        }
    }

    /**
     * Задание без исполнителя возвращается в очередь.
     *
     * @throws AssertionError если задание осталось в работе
     */
    @Test
    fun `задание без исполнителя возвращается в очередь`() {
        val jobId =
            queue.enqueue(
                kind = JobKind.FACES,
                subject = JobSubject.videofile(1),
                paramsJson = "{}",
                paramsHash = "h",
            )

        // Имитация жёсткой остановки: воркер убит, задание осталось в работе.
        TestDatabase.assumeDatabase().use { connection ->
            connection.prepareStatement("UPDATE tbl_jobs SET state = 'WORKING' WHERE id = ?").use { statement ->
                statement.setLong(1, jobId)
                statement.executeUpdate()
            }
        }
        assertEquals("WORKING", queue.find(jobId)?.state?.name, "подготовка: задание должно быть в работе")

        // Воркер поднимается — как после жёсткой остановки контейнера.
        val worker = worker()
        worker.start()
        try {
            val returned = awaitState(jobId, "WAITING")
            assertEquals(
                "WAITING",
                returned,
                "задание без исполнителя осталось в работе — состояние продолжает утверждать, что идёт работа",
            )
        } finally {
            worker.stop()
        }
    }

    /**
     * Ждёт, пока задание достигнет указанного состояния.
     *
     * @return состояние, на котором ожидание закончилось
     */
    private fun awaitState(
        jobId: Long,
        wanted: String,
    ): String {
        val deadline = System.currentTimeMillis() + stateWaitMillis
        var last = "нет задания"
        while (System.currentTimeMillis() < deadline) {
            last = queue.find(jobId)?.state?.name ?: "нет задания"
            if (last == wanted) {
                return last
            }
            Thread.sleep(statePollMillis)
        }
        return last
    }

    /** Собирает воркер для проверки. */
    private fun worker(): AdminJobWorker =
        AdminJobWorker(
            queue = queue,
            handlers = emptyMap(),
            artifactRegistry =
                ArtifactRegistry(
                    TestDatabase.assumeDatabase(),
                    FileSystemStorage(Files.createTempDirectory("syp-orphan")),
                ),
            db = TestDatabase.assumeDatabase(),
        )
}
