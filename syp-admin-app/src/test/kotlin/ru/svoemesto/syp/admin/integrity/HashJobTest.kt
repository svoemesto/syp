package ru.svoemesto.syp.admin.integrity

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobProgress
import ru.svoemesto.syp.core.jobs.JobState
import ru.svoemesto.syp.core.jobs.JobSubject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки задания подсчёта суммы `HASH`.
 *
 * Закрывают требования задачи T041:
 *
 * 1. **сумма совпадает с независимо посчитанной** тем же алгоритмом на том же
 *    файле — иначе эталон, с которым сверяется файл на машине пользователя,
 *    ничего не значит;
 * 2. **прогресс растёт монотонно по прочитанным байтам** и доходит до общего
 *    объёма файла (FR-003, М-11);
 * 3. **ошибка чтения переводит подсчёт в ошибку с текстом**, а запись
 *    справочника остаётся неудачной, а не готовой (FR-092);
 * 4. **незавершённый подсчёт не даёт актуальной суммы** — частично посчитанная
 *    сумма суммой не является.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HashJobTest {
    private lateinit var db: Db
    private lateinit var episodeStore: EpisodeStore
    private lateinit var registry: ChecksumRegistry

    /**
     * Поднимает доступ к базе и хранилища.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        episodeStore = EpisodeStore(db)
        registry = ChecksumRegistry(db)
    }

    /** Размер проверочного файла, байт: два блока по 4 Миб с запасом. */
    private val fileSize = 9L * 1024 * 1024

    /**
     * Создаёт проверочный файл заданного размера с известным содержимым.
     *
     * @param size размер файла, байт
     * @return путь к файлу
     */
    private fun newSourceFile(size: Long): Path {
        val file = Files.createTempFile("syp-hash", ".bin")
        val content = ByteArray(size.toInt())
        // Содержимое не повторяющееся: иначе ошибка чтения в середине файла
        // могла бы не дать заметного отличия от полного содержимого.
        for (index in content.indices) {
            content[index] = (index * 31 % 251).toByte()
        }
        Files.write(file, content)
        return file
    }

    /**
     * Заводит эпизод на проверочный файл.
     *
     * @param file путь к файлу
     * @return записанный эпизод
     */
    private fun newEpisode(file: Path): Episode {
        val movies = MovieStore(db)
        val movie = movies.create("Подсчёт ${System.nanoTime()}", file.parent.toString())
        return episodeStore.insert(
            Episode(
                movieId = movie.id!!,
                ordinal = 0,
                name = "S1E1",
                sourcePath = file.toString(),
                byteSize = fileSize,
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

    /**
     * Считает сумму файла тем же алгоритмом, но мимо системы.
     *
     * @param file путь к файлу
     * @return сумма 64 шестнадцатеричных символов в нижнем регистре
     */
    private fun referenceDigest(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Ставит задание вида `HASH` над эпизодом и отдаёт его.
     *
     * Задание записывается по-настоящему: запись справочника ссылается на
     * задание, и ссылка проверяется базой. Задание с выдуманным
     * идентификатором было бы проверкой несуществующего.
     *
     * @param episode эпизод
     * @return задание в состоянии `WORKING`
     */
    private fun newJob(episode: Episode): Job {
        val jobId =
            db.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_jobs (kind, state, subject_type, subject_id, params, params_hash, " +
                            "algorithm_version, progress_done, progress_total) " +
                            "VALUES ('HASH', 'WORKING', 'EPISODE', ?, ?::jsonb, ?, 'SHA-256', 0, 0) " +
                            "RETURNING id",
                    ).use { statement ->
                        statement.setLong(1, episode.id!!)
                        statement.setString(2, "{\"algorithm\":\"SHA-256\"}")
                        statement.setString(3, "0".repeat(64))
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            resultSet.getLong(1)
                        }
                    }
            }
        return Job(
            id = jobId,
            kind = JobKind.HASH,
            state = JobState.WORKING,
            subject = JobSubject.episode(episode.id!!),
            paramsJson = "{}",
            paramsHash = "0".repeat(64),
            algorithmVersion = "SHA-256",
            progress = JobProgress.EMPTY,
            errorText = null,
            createdAt = "2026-10-04T00:00:00Z",
            startedAt = "2026-10-04T00:00:01Z",
            finishedAt = null,
        )
    }

    @Test
    fun `сумма задания совпадает с независимо посчитанной`() {
        val file = newSourceFile(fileSize)
        val episode = newEpisode(file)
        val job = newJob(episode)
        val reported = mutableListOf<JobProgress>()

        val result = HashJob(episodeStore, registry, progressStep = 1024 * 1024).execute(job) { reported.add(it) }

        val current = registry.current(episode.id!!)
        assertNotNull(current, "после задания должна появиться актуальная сумма")
        assertEquals(referenceDigest(file), current!!.digest)
        assertEquals(ChecksumState.DONE, current.state)
        assertEquals(fileSize, current.byteSize)
        assertTrue(result.progressTotal >= fileSize, "общий объём работы известен: без него задание нельзя завершить")

        // Прогресс монотонен и доходит до общего объёма.
        reported.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.done >= previous.done, "прогресс уехал назад: ${previous.done} → ${next.done}")
            assertTrue(next.total >= previous.total, "общий объём уменьшился: ${previous.total} → ${next.total}")
        }
        assertEquals(fileSize, reported.last().done, "последнее сообщение прогресса обязано равняться объёму файла")
        assertTrue(reported.size >= 3, "прогресс сообщается по ходу чтения, а не один раз в конце: ${reported.size} сообщений")
    }

    @Test
    fun `ошибка чтения переводит подсчёт в ошибку с текстом`() {
        val file = newSourceFile(fileSize)
        val episode = newEpisode(file)
        val handler = HashJob(episodeStore, registry)
        Files.delete(file)

        val failure = assertFailsWith<HashFailed> { handler.execute(newJob(episode)) {} }

        assertTrue(failure.text.contains("не удалось прочитать"), "текст ошибки объясняет, что именно не вышло: ${failure.text}")
        val entry = registry.latest(episode.id!!)
        assertEquals(ChecksumState.ERROR, entry?.state, "подсчёт должен остаться в ошибке, а не в готовом состоянии")
        assertNotNull(entry?.errorText)
        assertFalse(registry.isUsable(episode.id!!), "у эпизода без прочитанного файла актуальной суммы быть не может")
    }
}
