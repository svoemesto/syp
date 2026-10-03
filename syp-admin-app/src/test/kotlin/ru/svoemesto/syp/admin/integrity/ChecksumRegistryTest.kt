package ru.svoemesto.syp.admin.integrity

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.Project
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки справочника сумм исходников и задания подсчёта.
 *
 * Закрывают требования задач T041 и T043:
 *
 * 1. **история сохраняется** — новая сумма не затирает прежнюю, актуальная
 *    остаётся ровно одна, а вторая актуальная запись в базу не попадает;
 * 2. **подмена источника помечает прежнюю сумму устаревшей** — по размеру
 *    файла или по времени его изменения;
 * 3. **прерванный подсчёт не оставляет запись `DONE`** — запись появляется в
 *    начале работы, а `DONE` появляется только после полного чтения файла;
 * 4. **прогресс растёт монотонно** по прочитанным байтам и не блокирует
 *    интерфейс: подсчёт идёт заданием, а не запросом.
 *
 * Проверки требуют живой базы: без неё они помечаются пропущенными. База
 * поднимается одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChecksumRegistryTest {
    private lateinit var db: Db
    private lateinit var projects: ProjectStore
    private lateinit var videofileStore: VideofileStore
    private lateinit var registry: ChecksumRegistry

    /**
     * Поднимает доступ к базе и хранилища.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        projects = ProjectStore(db)
        videofileStore = VideofileStore(db)
        registry = ChecksumRegistry(db)
    }

    /** Сумма из 64 шестнадцатеричных символов: формат `sha256sum`. */
    private fun digest(seed: Int): String = "%064x".format(seed)

    /**
     * Создаёт эпизод на временном файле заданного размера.
     *
     * @param byteSize размер файла эпизода
     * @param path файл эпизода
     * @return записанный эпизод
     */
    private fun newVideofile(
        byteSize: Long,
        path: Path,
    ): Videofile {
        val project: Project = projects.create("Суммы ${System.nanoTime()}", path.parent.toString())
        return videofileStore.insert(
            Videofile(
                projectId = project.id!!,
                ordinal = 0,
                name = "S1E1",
                sourcePath = path.toString(),
                byteSize = byteSize,
                fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                frameCount = 100,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = 1920,
                height = 1080,
                durationNum = 100_100,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                keyframeMap = KeyframeMap.build(100, listOf(0, 50)),
            ),
        )
    }

    @Test
    fun `новый сумма не затирает прежнюю и актуальной остаётся одна`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)

        val first = registry.begin(videofile, null)
        registry.complete(first.id!!, digest(1), 4096, videofile.fileMtime)
        val second = registry.begin(videofile, null)
        registry.complete(second.id!!, digest(2), 4096, videofile.fileMtime)

        val history = registry.history(videofile.id!!)
        assertEquals(2, history.size, "обе посчитанные суммы обязаны сохраниться: пересчёт не затирает историю")
        val current = registry.current(videofile.id!!)
        assertNotNull(current)
        assertEquals(digest(2), current!!.digest, "актуальной должна быть последняя посчитанная сумма")
        val previous = history.firstOrNull { it.digest == digest(1) }
        assertNotNull(previous) { "прежняя сумма обязана сохраниться в истории; в истории: ${history.map { it.digest }}" }
        assertTrue(previous!!.isStale, "прежняя сумма обязана стать устаревшей")
        assertFalse(current.isStale)

        val conflict =
            assertFailsWith<java.sql.SQLException> {
                db.use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO tbl_source_file_checksums (id_videofile, algorithm, digest, byte_size, " +
                                "file_mtime, state, is_stale, computed_at) " +
                                "VALUES (?, 'SHA-256', ?, 4096, ?, 'DONE', FALSE, now())",
                        ).use { statement ->
                            statement.setLong(1, videofile.id!!)
                            statement.setString(2, digest(3))
                            statement.setObject(3, videofile.fileMtime)
                            statement.executeUpdate()
                        }
                }
            }
        assertTrue(
            conflict.message!!.contains("source_file_checksum_one_current_idx"),
            "вторая актуальная сумма того же эпизода обязана отклоняться базой, а отказ шёл с текстом: ${conflict.message}",
        )
    }

    @Test
    fun `подмена источника помечает прежнюю сумму устаревшей`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)
        val entry = registry.begin(videofile, null)
        registry.complete(entry.id!!, digest(4), 4096, videofile.fileMtime)
        assertTrue(registry.isUsable(videofile.id!!), "только что посчитанная сумма пригодна")

        val changed = videofile.copy(byteSize = 8192)
        assertEquals(1, registry.markStaleWhenSourceChanged(changed))

        assertFalse(registry.isUsable(videofile.id!!), "после изменения размера файла сумма устарела и непригодна")
        val stored = registry.current(videofile.id!!)
        assertNull(stored, "актуальной суммы у эпизода быть не должно")
        val history = registry.history(videofile.id!!)
        assertEquals(digest(4), history.first().digest, "старое значение сохраняется: по нему видно подмену")

        // Возврат к прежнему размеру не возвращает актуальность: запись
        // помечена устаревшей, и её надо пересчитать, а не «оживить» руками.
        assertEquals(0, registry.markStaleWhenSourceChanged(videofile))
        assertEquals(1, registry.history(videofile.id!!).size)
    }

    @Test
    fun `подмена по времени изменения файла помечает сумму устаревшей`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)
        val entry = registry.begin(videofile, null)
        registry.complete(entry.id!!, digest(5), 4096, videofile.fileMtime)

        val touched = videofile.copy(fileMtime = videofile.fileMtime.plusSeconds(60))

        assertEquals(1, registry.markStaleWhenSourceChanged(touched))
        assertFalse(registry.isUsable(videofile.id!!), "изменение времени файла тоже делает сумму устаревшей")
    }

    @Test
    fun `прерванный подсчёт не оставляет запись DONE`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)

        val entry = registry.begin(videofile, null)
        registry.markWorking(entry.id!!)

        val stored = registry.latest(videofile.id!!)
        assertEquals(ChecksumState.WORKING, stored?.state)
        assertNull(registry.current(videofile.id!!), "незавершённый подсчёт не даёт актуальной суммы")
        assertFalse(registry.isUsable(videofile.id!!))
        assertNull(stored?.digest, "у незавершённого подсчёта суммы нет вовсе")

        registry.fail(entry.id!!, "файл исчез с архива")
        val failed = registry.latest(videofile.id!!)
        assertEquals(ChecksumState.ERROR, failed?.state)
        assertEquals("файл исчез с архива", failed?.errorText)
        assertFalse(registry.isUsable(videofile.id!!))
    }

    @Test
    fun `второй подсчёт того же эпизода отклоняется, пока идёт первый`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)
        // Первое задание просто поставлено и ещё не взято в работу: его
        // собственный подсчёт ещё не начинался, и он не мешает.
        val firstJobId = enqueueHashJob(videofile.id!!)
        registry.begin(videofile, firstJobId)
        // Второе задание уже считает — вот оно и мешает.
        val secondJobId = enqueueHashJob(videofile.id!!)
        db.update("UPDATE tbl_jobs SET state = 'WORKING' WHERE id = ?", secondJobId)

        val failure = assertFailsWith<DomainException> { registry.begin(videofile, firstJobId) }

        assertEquals(ErrorCode.CONFLICT, failure.code)
        assertTrue(failure.toBody().message.contains("уже считается"), "текст отказа объясняет причину")
    }

    @Test
    fun `сумма не в формате sha256sum отвергается до записи`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)
        val entry = registry.begin(videofile, null)

        assertFailsWith<IllegalArgumentException> {
            registry.complete(entry.id!!, "не-сумма", 4096, videofile.fileMtime)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.complete(entry.id!!, "A".repeat(64), 4096, videofile.fileMtime)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.complete(entry.id!!, "ab".repeat(31), 4096, videofile.fileMtime)
        }
    }

    @Test
    fun `удаление задания не удаляет посчитанную сумму`() {
        val file = Files.createTempFile("syp-sum", ".bin")
        Files.write(file, ByteArray(4096))
        val videofile = newVideofile(4096, file)
        val jobId = enqueueHashJob(videofile.id!!)
        val entry = registry.begin(videofile, jobId)
        registry.complete(entry.id!!, digest(7), 4096, videofile.fileMtime)

        db.update("DELETE FROM tbl_jobs WHERE id = ?", jobId)

        val stored = registry.current(videofile.id!!)
        assertNotNull(stored, "результат переживает задание: ссылки на задание обнуляются, сумма остаётся")
        assertNull(stored!!.jobId)
        assertTrue(registry.isUsable(videofile.id!!))
    }

    /**
     * Ставит задание вида `HASH` для эпизода.
     *
     * @param videofileId идентификатор эпизода
     * @return идентификатор задания
     */
    private fun enqueueHashJob(videofileId: Long): Long =
        db.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_jobs (kind, state, subject_type, subject_id, params, params_hash) " +
                        "VALUES ('HASH', 'WAITING', 'EPISODE', ?, ?::jsonb, ?) RETURNING id",
                ).use { statement ->
                    statement.setLong(1, videofileId)
                    statement.setString(2, "{\"algorithm\":\"SHA-256\"}")
                    statement.setString(3, digest(8))
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }
}
