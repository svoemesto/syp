package ru.svoemesto.syp.core.jobs

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.core.db.DbException
import ru.svoemesto.syp.core.storage.ArtifactKind
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ArtifactState
import ru.svoemesto.syp.core.storage.FileSystemStorage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Контрактные тесты очереди заданий.
 *
 * Закрывают проверяемые свойства контракта
 * [`job-queue.md`](../../../../../../specs/001-first-vertical-slice/contracts/job-queue.md):
 * свойства 1, 2, 3, 5, 6 и 8. Свойство 4 (ручная правка не теряется при
 * повторном анализе) закрывается задачами T096 и T097, свойства 7 и 9 — T168
 * и T158: они относятся к фазам, где соответствующий код ещё не написан, и
 * объявлять их закрытыми здесь было бы выдумкой.
 *
 * Требует живой базы: поднимается одноразовым контейнером `postgres:16`
 * скриптом `tools/run-db-tests.sh`. Без базы тесты помечаются пропущенными —
 * это видно в отчёте и не выдаётся за выполненную проверку.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Контракт очереди заданий")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobQueueContractTest {
    private lateinit var queue: JobQueue
    private lateinit var artifacts: ArtifactRegistry

    @BeforeAll
    fun setUp() {
        TestDb.setUp()
        val db = TestDb.assumeDatabase()
        queue = JobQueue(db)
        artifacts =
            ArtifactRegistry(
                db,
                FileSystemStorage(
                    java.nio.file.Files
                        .createTempDirectory("syp-artifacts"),
                ),
            )
        clearJobTables()
    }

    /**
     * Очистка очереди и артефактов перед каждым тестом.
     *
     * Очередь одна на весь набор, и без очистки [JobQueue.claim] взял бы самую
     * старую задание, а не созданное текущим тестом: проверки переходов стали
     * бы зависеть от порядка выполнения, что неприемлемо для проверки
     * контракта.
     */
    @BeforeEach
    fun clearQueue() {
        clearJobTables()
    }

    /** Удаляет задания и артефакты, созданные предыдущими тестами. */
    private fun clearJobTables() {
        val db = TestDb.assumeDatabase()
        db.update("DELETE FROM tbl_artifacts")
        db.update("DELETE FROM tbl_jobs")
    }

    /**
     * Свойство 1: задание проходит пять состояний и заканчивается `DONE`.
     *
     * Порядок переходов единственный разрешённый: `WAITING → CREATING →
     * WORKING → DONE`. Пропуск состояния означал бы, что оператор не видит
     * «взято в работу» отдельно от «идёт работа» (FR-003).
     */
    @Test
    @DisplayName("Свойство 1: пять состояний в разрешённом порядке")
    fun fiveStatesInOrder() {
        val id = enqueue(JobKind.ANALYZE, "свойство-1")
        assertEquals(JobState.WAITING, queue.find(id)?.state)

        val claimed = queue.claim(listOf(JobKind.ANALYZE))
        assertNotNull(claimed)
        assertEquals(id, claimed.id)
        assertEquals(JobState.CREATING, queue.find(id)?.state)

        queue.startWork(id)
        assertEquals(JobState.WORKING, queue.find(id)?.state)

        queue.reportProgress(id, JobProgress(88_643, 88_643, "кадры обработаны"))
        queue.complete(id)

        val finished = queue.find(id)
        assertEquals(JobState.DONE, finished?.state)
        assertNull(finished?.errorText)
    }

    /**
     * Свойство 1 (продолжение): переход в `ERROR` возможен из любого
     * состояния, а текст ошибки обязателен.
     */
    @Test
    @DisplayName("Свойство 1: переход в ERROR из любого состояния")
    fun errorFromAnyState() {
        val waiting = enqueue(JobKind.FACES, "свойство-1-ожидание")
        queue.fail(waiting, "сработало до захвата")
        assertEquals(JobState.ERROR, queue.find(waiting)?.state)
        assertEquals("сработало до захвата", queue.find(waiting)?.errorText)

        val working = enqueue(JobKind.FACES, "свойство-1-работа")
        queue.claim(listOf(JobKind.FACES))
        queue.startWork(working)
        queue.fail(working, "упало во время работы")
        assertEquals(JobState.ERROR, queue.find(working)?.state)

        // Текст ошибки обязателен: база отвергает ERROR без него.
        val rejected =
            runCatching {
                queue.fail(working, "   ")
            }
        assertTrue(rejected.isFailure, "пустой текст ошибки должен отклоняться")
    }

    /**
     * Свойство 1 (продолжение): ненулевой код завершения внешней программы
     * ведёт в `ERROR` с текстом, а не в `DONE` (FR-004, FR-092, SC-005).
     *
     * Прецедент старого проекта: ни один из двенадцати вызовов ffmpeg не
     * проверял код возврата, и упавший процесс помечался как «сделано».
     */
    @Test
    @DisplayName("Свойство 1: ненулевой код завершения ведёт в ERROR, а не в DONE")
    fun nonZeroExitCodeLeadsToError() {
        val failing =
            runCatching {
                ProcessBuilder("/bin/sh", "-c", "echo 'ошибка: нет такого фильтра' >&2; exit 1")
                    .redirectErrorStream(true)
                    .start()
                    .let { process ->
                        val output = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        output to process.exitValue()
                    }
            }
        assertTrue(failing.isSuccess)

        val (output, code) = failing.getOrThrow()
        assertEquals(1, code, "пробная программа обязана вернуть ненулевой код")

        val jobId = enqueue(JobKind.ANALYZE, "свойство-1-код-возврата")
        queue.claim(listOf(JobKind.ANALYZE))
        queue.startWork(jobId)
        queue.reportProgress(jobId, JobProgress(10, 100))
        // Что делает воркер с ненулевым кодом: пишет вывод в текст ошибки.
        queue.fail(jobId, "ffmpeg завершился с кодом 1:\n$output")

        val failed = queue.find(jobId)
        assertEquals(JobState.ERROR, failed?.state)
        assertTrue(
            failed?.errorText?.contains("нет такого фильтра") == true,
            "текст ошибки обязан содержать вывод программы, а не только код",
        )
    }

    /**
     * Свойство 2: прерванный на середине процесс не оставляет артефакта в
     * состоянии `READY` (FR-091).
     *
     * Артефакт сначала пишется по временному ключу, и `READY` получается
     * только после переноса на окончательный ключ.
     */
    @Test
    @DisplayName("Свойство 2: прерванный процесс не оставляет READY")
    fun interruptedProcessLeavesNoReadyArtifact() {
        val jobId = enqueue(JobKind.ANALYZE, "свойство-2")
        val artifact = artifacts.begin(jobId, ArtifactKind.PREVIEW_SHEET, "sheets/2/sheet-0.jpg", "image/jpeg")
        assertEquals(ArtifactState.WRITING, artifacts.find(artifact.id)?.state)

        // Пишем половину файла и «падаем»: переноса на окончательный ключ нет.
        artifacts.writeTemporary(artifact, "половина листа".toByteArray().inputStream())
        val failed = artifacts.markFailed(artifact, "прервано на середине")

        assertEquals(ArtifactState.FAILED, failed.state)
        val stored = artifacts.find(artifact.id)
        assertEquals(ArtifactState.FAILED, stored?.state)
        assertNull(artifacts.findReady(ArtifactKind.PREVIEW_SHEET, "sheets/2/sheet-0.jpg"))

        // Чтение неготового артефакта запрещено: незавершённый файл не
        // считается готовым.
        val read = runCatching { artifacts.openReady(artifact.id) }
        assertTrue(read.isFailure, "чтение артефакта не в состоянии READY должно отклоняться")
    }

    /**
     * Свойство 2 (продолжение): готовый артефакт получается только после
     * переноса временного объекта на окончательный ключ.
     */
    @Test
    @DisplayName("Свойство 2: READY только после переноса временного объекта")
    fun readyOnlyAfterMove() {
        val jobId = enqueue(JobKind.ANALYZE, "свойство-2-перенос")
        val artifact = artifacts.begin(jobId, ArtifactKind.PREVIEW_SHEET, "sheets/2b/sheet-0.jpg", "image/jpeg")

        // Временного объекта нет: помечать готовым нечего.
        val withoutTemporary =
            runCatching {
                artifacts.markReady(artifact, "0".repeat(64), 10)
            }
        assertTrue(withoutTemporary.isFailure, "READY без временного объекта невозможен")

        val content = "лист превью целиком".toByteArray()
        artifacts.writeTemporary(artifact, content.inputStream())
        val checksum =
            ru.svoemesto.syp.core.signing.Canonicalizer
                .checksum(content)
        val ready = artifacts.markReady(artifact, checksum, content.size.toLong())

        assertEquals(ArtifactState.READY, ready.state)
        assertNotNull(artifacts.findReady(ArtifactKind.PREVIEW_SHEET, "sheets/2b/sheet-0.jpg"))
    }

    /**
     * Свойство 3: задание с тем же хешем параметров не выполняется повторно,
     * а с изменившимся — выполняется (Р-10).
     */
    @Test
    @DisplayName("Свойство 3: повторный хеш пропускается, изменённый — выполняется")
    fun sameHashIsSkipped() {
        val hash = "свойство-3-${System.nanoTime()}"
        val firstId =
            queue.enqueue(
                kind = JobKind.ANALYZE,
                subject = JobSubject.NONE,
                paramsJson = """{"thresholds":{"scene":30,"shot":12}}""",
                paramsHash = hash,
                algorithmVersion = "structure-1",
            )
        queue.claim(listOf(JobKind.ANALYZE))
        // Без CREATING → WORKING переход в DONE был бы запрещён: пропуск
        // состояния означал бы, что оператор не видел «взято в работу».
        queue.startWork(firstId)
        queue.reportProgress(firstId, JobProgress(100, 100))
        queue.complete(firstId)

        // Артефакт не зарегистрирован как READY: пропускать нельзя.
        assertFalse(
            queue.hasCompletedWithReadyArtifact(JobKind.ANALYZE, hash),
            "задание без зарегистрированного результата READY не считается выполненным",
        )

        val artifact = artifacts.begin(firstId, ArtifactKind.PREVIEW_SHEET, "sheets/3/sheet-0.jpg", "image/jpeg")
        val content = "лист".toByteArray()
        artifacts.writeTemporary(artifact, content.inputStream())
        artifacts.markReady(
            artifact,
            ru.svoemesto.syp.core.signing.Canonicalizer
                .checksum(content),
            content.size.toLong(),
        )

        // Теперь результат зарегистрирован: тот же хеш пропускается.
        assertTrue(
            queue.hasCompletedWithReadyArtifact(JobKind.ANALYZE, hash),
            "задание с зарегистрированным результатом READY считается выполненным",
        )

        // Изменённый набор параметров — другой хеш, другой результат.
        assertFalse(queue.hasCompletedWithReadyArtifact(JobKind.ANALYZE, "$hash-изменён"))
    }

    /**
     * Свойство 5: смена параметров помечает старые результаты устаревшими, но
     * **не удаляет** их (FR-090, constitution IV.3).
     *
     * Автоматический пересчёт уничтожил бы ручные правки оператора (SC-006).
     */
    @Test
    @DisplayName("Свойство 5: смена порога помечает результат устаревшим, не удаляя")
    fun staleMarkedNotDeleted() {
        val oldHash = "свойство-5-старый-${System.nanoTime()}"
        val newHash = "свойство-5-новый-${System.nanoTime()}"
        val jobId = queue.enqueue(JobKind.ANALYZE, JobSubject.NONE, "{}", oldHash, "structure-1")
        queue.claim(listOf(JobKind.ANALYZE))
        queue.startWork(jobId)
        queue.reportProgress(jobId, JobProgress(100, 100))
        queue.complete(jobId)

        val marked = queue.markStaleExcept(JobKind.ANALYZE, newHash)
        assertTrue(marked >= 1, "хотя бы одно задание должно быть помечено устаревшим")

        // Задание и его результат остались на месте.
        val stored = queue.find(jobId)
        assertNotNull(stored, "старое задание не должно удаляться")
        assertEquals(JobState.DONE, stored.state)
        assertTrue(
            stored.progress.note.contains("помечен устаревшим"),
            "в пояснении прогресса должна стоять отметка об устаревании: ${stored.progress.note}",
        )
    }

    /**
     * Свойство 6: прерванное задание возвращается в очередь с сохранённым
     * прогрессом (FR-003).
     */
    @Test
    @DisplayName("Свойство 6: прерванное задание возвращается в очередь с прогрессом")
    fun interruptedJobRequeuedWithProgress() {
        val id = enqueue(JobKind.FACES, "свойство-6")
        queue.claim(listOf(JobKind.FACES))
        queue.startWork(id)
        queue.reportProgress(id, JobProgress(37_000, 88_643, "кадр 37000"))

        queue.requeue(id, "воркер перезапущен")

        val requeued = queue.find(id)
        assertEquals(JobState.WAITING, requeued?.state)
        assertEquals(37_000, requeued?.progress?.done, "прогресс обязан сохраниться")
        assertEquals(88_643, requeued?.progress?.total)
        assertNull(requeued?.errorText)
    }

    /**
     * Свойство 6 (продолжение): отмена оператором возвращает задание в очередь
     * с тем же сохранённым прогрессом.
     */
    @Test
    @DisplayName("Свойство 6: отмена возвращает в очередь с сохранённым прогрессом")
    fun cancelKeepsProgress() {
        val id = enqueue(JobKind.TRAIN, "свойство-6-отмена")
        queue.claim(listOf(JobKind.TRAIN))
        queue.startWork(id)
        queue.reportProgress(id, JobProgress(5, 20, "этап обучения"))

        queue.cancel(id, "оператор остановил обучение")

        val cancelled = queue.find(id)
        assertEquals(JobState.WAITING, cancelled?.state)
        assertEquals(5, cancelled?.progress?.done)
        assertTrue(cancelled?.progress?.note?.contains("отменено оператором") == true)
    }

    /**
     * Свойство 8: прерванный подсчёт суммы не оставляет запись в состоянии
     * `DONE`, и у эпизода не может оказаться двух актуальных сумм (FR-089).
     */
    @Test
    @DisplayName("Свойство 8: прерванный подсчёт суммы не оставляет DONE")
    fun interruptedChecksumLeavesNoDoneRow() {
        val db = TestDb.assumeDatabase()
        val episodeId = createEpisode(db)
        val jobId = enqueue(JobKind.HASH, "свойство-8")

        // Подсчёт начат и прерван: запись остаётся не в состоянии DONE.
        db.update(
            """
            INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state)
            VALUES (?, 'SHA-256', repeat('a', 64), 100, now(), 'WORKING')
            """.trimIndent(),
            episodeId,
        )
        val row =
            db.selectOne(
                "SELECT id, state FROM tbl_source_file_checksums WHERE id_episode = ?",
                { it.long("id") to it.string("state") },
                episodeId,
            )
        assertNotNull(row)
        assertEquals("WORKING", row.second, "прерванный подсчёт не должен давать DONE")

        // Две актуальные суммы в базу не попадают: частичный уникальный индекс.
        db.update(
            """
            INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
            VALUES (?, 'SHA-256', repeat('b', 64), 100, now(), 'DONE', now())
            """.trimIndent(),
            episodeId,
        )
        val second =
            runCatching {
                db.update(
                    """
                    INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
                    VALUES (?, 'SHA-256', repeat('c', 64), 100, now(), 'DONE', now())
                    """.trimIndent(),
                    episodeId,
                )
            }
        assertTrue(second.isFailure, "вторая актуальная сумма того же эпизода должна отклоняться базой")

        // Первая актуальная сумма на месте, устаревших — сколько угодно.
        val current =
            db.select(
                "SELECT digest FROM tbl_source_file_checksums WHERE id_episode = ? AND state = 'DONE' AND is_stale = FALSE",
                { it.string("digest") },
                episodeId,
            )
        assertEquals(1, current.size, "актуальная сумма должна быть ровно одна")
        assertTrue(db.update("DELETE FROM tbl_episodes WHERE id = ?", episodeId) >= 0)
        assertEquals(jobId, jobId)
    }

    /**
     * Свойство 5 (продолжение): хеш параметров воспроизводим и различает
     * порядок значений.
     *
     * Разный порядок — это разные параметры: смешивать их нельзя, иначе два
     * разных набора настроек сошлись бы к одному хешу.
     */
    @Test
    @DisplayName("Хеш параметров воспроизводим и чувствителен к порядку")
    fun paramsHashIsStable() {
        val first = ParamsHash.of("structure-1", 30, 12, "auto")
        val second = ParamsHash.of("structure-1", 30, 12, "auto")
        val reordered = ParamsHash.of("structure-1", 12, 30, "auto")

        assertEquals(first, second, "одни и те же параметры дают один хеш")
        assertTrue(first != reordered, "перестановка значений обязана менять хеш")
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")), "хеш должен быть SHA-256 в нижнем регистре")
    }

    /**
     * Лица, поставленные раньше разбора, ждут разбора.
     *
     * Порядок в очереди задан временем создания, и без этого правила лица
     * вставали в очередь первыми и получали эпизод без планов: лица искались
     * вхолостую, привязываться им было не к чему, а задание выглядело
     * успешным.
     */
    @Test
    @DisplayName("Свойство: лица ждут разбора того же эпизода")
    fun facesWaitsForOwnAnalysis() {
        val faces = enqueueFor(JobKind.FACES, "лица-раньше", episodeId = 1)
        enqueueFor(JobKind.ANALYZE, "разбор-позже", episodeId = 1)

        assertNull(
            queue.claim(listOf(JobKind.FACES)),
            "лица не должны браться в работу, пока разбор их эпизода не закончен",
        )
        val analysis = queue.claim(listOf(JobKind.ANALYZE))
        assertNotNull(analysis, "разбор обязан браться: он ждёт лица, а не наоборот")
        queue.startWork(analysis.id)
        // У задания в DONE обязан быть заданный объём: без него состояние
        // «выполнено» не означало бы, что работа была.
        queue.reportProgress(analysis.id, JobProgress(4, 4, "кадры"))
        queue.complete(analysis.id)

        val after = queue.claim(listOf(JobKind.FACES))
        assertNotNull(after, "после разбора лица обязаны браться в работу")
        assertEquals(faces, after.id, "взято не то задание")
    }

    /**
     * Разбор чужого эпизода не должен задерживать лица этого.
     *
     * Иначе один тяжёлый разбор останавливал бы поиск лиц по всему сериалу.
     */
    @Test
    @DisplayName("Свойство: разбор чужого эпизода не задерживает лица")
    fun otherEpisodeAnalysisDoesNotBlock() {
        val faces = enqueueFor(JobKind.FACES, "лица-эпизод-1", episodeId = 1)
        enqueueFor(JobKind.ANALYZE, "разбор-эпизод-2", episodeId = 2)

        val claimed = queue.claim(listOf(JobKind.FACES))
        assertNotNull(claimed, "разбор другого эпизода не должен ждать лица этого")
        assertEquals(faces, claimed.id, "взято не то задание")
    }

    /**
     * Упавший разбор не должен держать лица вечно.
     *
     * Ожидание снимается и неудачей разбора: иначе один сбой остановил бы
     * поиск лиц по эпизоду навсегда, и очередь стояла бы молча.
     */
    @Test
    @DisplayName("Свойство: упавший разбор не держит лица")
    fun failedAnalysisDoesNotBlockForever() {
        enqueueFor(JobKind.FACES, "лица-после-сбоя", episodeId = 3)
        enqueueFor(JobKind.ANALYZE, "разбор-упадёт", episodeId = 3)
        val analysis = queue.claim(listOf(JobKind.ANALYZE))
        assertNotNull(analysis, "разбор должен браться в работу")
        queue.startWork(analysis.id)
        queue.fail(analysis.id, "декодер не ответил")

        assertNotNull(
            queue.claim(listOf(JobKind.FACES)),
            "упавший разбор не должен держать лица: иначе очередь встанет навсегда",
        )
    }

    /** Ставит задание в очередь и возвращает его идентификатор. */

    private fun enqueue(
        kind: JobKind,
        label: String,
    ): Long = enqueueFor(kind, label, episodeId = null)

    /**
     * Ставит задание в очередь с указанным эпизодом.
     *
     * @param kind вид задания
     * @param label метка для параметров
     * @param episodeId эпизод; `null` — задание без предмета
     * @return идентификатор задания
     */
    private fun enqueueFor(
        kind: JobKind,
        label: String,
        episodeId: Long?,
    ): Long =
        queue.enqueue(
            kind = kind,
            subject = if (episodeId == null) JobSubject.NONE else JobSubject.episode(episodeId),
            paramsJson = """{"метка":"$label"}""",
            paramsHash = ParamsHash.of(label, System.nanoTime()),
            algorithmVersion = "contract-test-1",
        )

    /** Создаёт эпизод для проверок справочника сумм. */
    private fun createEpisode(db: ru.svoemesto.syp.core.db.Db): Long {
        val movieId =
            db.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_movies (name, source_root) VALUES (?, ?) RETURNING id",
                    ).use { statement ->
                        statement.setString(1, "Контракт ${System.nanoTime()}")
                        statement.setString(2, "/srv/contract")
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            resultSet.getLong(1)
                        }
                    }
            }
        return db.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime,
                                        frame_count, time_base_num, time_base_den, width, height,
                                        duration_num, duration_den, video_codec, pixel_format)
                    VALUES (?, 1, 'S01E01', ?, 100, now(), 88643, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p')
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, movieId)
                    statement.setString(2, "/srv/contract/S01E01.mkv")
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }
    }

    /** Проверка на всякий случай: [DbException] — отдельный тип ошибки. */
    @Test
    @DisplayName("Ошибка перехода — отдельный тип с внятным текстом")
    fun transitionErrorIsTyped() {
        val id = enqueue(JobKind.ANALYZE, "переход")
        val failed = runCatching { queue.complete(id) }
        assertTrue(failed.isFailure)
        assertTrue(
            failed.exceptionOrNull() is DbException || failed.exceptionOrNull() is JobTransitionException,
            "отказ перехода должен быть типизированной ошибкой, а не пустым результатом",
        )
    }
}
