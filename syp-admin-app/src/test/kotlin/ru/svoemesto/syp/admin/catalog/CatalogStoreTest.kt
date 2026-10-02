package ru.svoemesto.syp.admin.catalog

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.core.db.Db
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Доступ к базе для проверок домена каталога.
 *
 * Проверки требуют живой базы: без неё они помечаются пропущенными, а не
 * падающими. База поднимается одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`, параметры подключения приходят через окружение.
 *
 * Вспомогательный класс повторяет `TestDb` модуля `syp-core`: общие для
 * модулей тестовые исходники Gradle не разделяет, а выносить их в отдельный
 * источник ради одной страницы кода — переделка уже слитой работы.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object TestDatabase {
    /** Имя переменной окружения со строкой подключения. */
    const val ENV_URL: String = "SYP_TEST_DB_URL"

    /** Имя переменной окружения с пользователем базы. */
    const val ENV_USER: String = "SYP_TEST_DB_USER"

    /** Имя переменной окружения с паролем базы. */
    const val ENV_PASSWORD: String = "SYP_TEST_DB_PASSWORD"

    /** Подключена ли база для тестов. */
    val isAvailable: Boolean
        get() = !System.getenv(ENV_URL).isNullOrBlank()

    /**
     * Отдаёт подключение к тестовой базе.
     *
     * @return готовый доступ к базе
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    fun assumeDatabase(): Db {
        val url = System.getenv(ENV_URL)
        if (url.isNullOrBlank()) {
            throw org.opentest4j.TestAbortedException(
                "Переменная $ENV_URL не задана: проверки домена каталога пропущены. " +
                    "Поднимите базу через bash tools/run-db-tests.sh",
            )
        }
        val db = Db(url, System.getenv(ENV_USER) ?: "postgres", System.getenv(ENV_PASSWORD) ?: "postgres")
        db.use { connection ->
            connection.createStatement().use { statement -> statement.execute("SELECT 1") }
        }
        return db
    }
}

/**
 * Проверки домена каталога поверх готовых миграций.
 *
 * Закрываются требования задачи T030: корень каталога сериала — абсолютный путь
 * без завершающего слэша; путь серии абсолютный; одна серия принадлежит ровно
 * одному сериалу; удаление сериала каскадом уносит производные данные.
 *
 * Заодно проверяется то, ради чего домен и написан: запись идёт **только** при
 * реальном изменении значений, а карта ключевых кадров возвращается из базы
 * байт в байт (constitution III, ADR-0001).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogStoreTest {
    private lateinit var db: Db
    private lateinit var movies: MovieStore
    private lateinit var episodeStore: EpisodeStore
    private lateinit var locations: LocationStore

    /**
     * Поднимает доступ к базе и хранилища домена.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята: весь
     *   набор проверок пропускается целиком
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        movies = MovieStore(db)
        episodeStore = EpisodeStore(db)
        locations = LocationStore(db)
    }

    /**
     * Создаёт сериал с уникальным именем.
     *
     * @param root корень каталога сериала
     * @return созданный сериал
     */
    private fun newMovie(root: String): Movie = movies.create("Проверка ${System.nanoTime()}", root)

    /**
     * Создаёт серию с заданной картой ключевых кадров.
     *
     * @param movie сериал-владелец
     * @param path путь к файлу серии
     * @param keyframes карта ключевых кадров
     * @return записанная серия
     */
    private fun newEpisode(
        movie: Movie,
        path: String,
        keyframes: KeyframeMap,
    ): Episode =
        episodeStore.insert(
            Episode(
                movieId = movie.id!!,
                ordinal = 0,
                name = "S01E01",
                sourcePath = path,
                byteSize = 5_598_286_865,
                fileMtime = java.time.OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                frameCount = keyframes.frameCount,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = 1920,
                height = 1080,
                durationNum = 88_731_643,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                audioCodec = "ac3",
                audioChannels = 6,
                audioSampleRate = 48_000,
                keyframeMap = keyframes,
            ),
        )

    @Test
    fun `сериал записывается и читается со своим хешем`() {
        val created = newMovie("/srv/got")

        assertNotNull(created.id)
        assertEquals("/srv/got", created.sourceRoot)
        assertNotNull(created.recordHash) { "записанная строка обязана получить хеш значений" }
        assertNotNull(created.createdAt)

        val loaded = movies.find(created.id!!)

        assertEquals(created, loaded)
    }

    @Test
    fun `корень каталога сериала проверяется до записи`() {
        assertFailsWith<IllegalArgumentException> { movies.create("Без корня", "got") }
        assertFailsWith<IllegalArgumentException> { movies.create("Со слэшем", "/srv/got/") }
        assertFailsWith<IllegalArgumentException> { movies.create("   ", "/srv/got") }
    }

    @Test
    fun `дубль названия сериала даёт внятный отказ`() {
        val name = "Повтор ${System.nanoTime()}"
        movies.create(name, "/srv/got")

        val failure = assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> { movies.create(name, "/srv/got") }

        assertEquals(ru.svoemesto.syp.core.contract.ErrorCode.CONFLICT, failure.code)
        assertTrue(failure.toBody().message.contains(name))
    }

    @Test
    fun `сохранение без изменений не переписывает строку`() {
        val movie = newMovie("/srv/got")

        assertTrue(!movies.save(movie), "повторное сохранение без изменений не должно переписывать строку")
        assertTrue(movies.save(movie.copy(name = movie.name + " (уточнён)")), "изменение значения обязано переписывать строку")
        assertEquals(movie.name + " (уточнён)", movies.find(movie.id!!)?.name)
    }

    @Test
    fun `серия хранит карту ключевых кадров байт в байт`() {
        val movie = newMovie("/srv/got")
        val keyframes = KeyframeMap.build(frameCount = 88_643, keyframes = listOf(0, 240, 88_642))
        val path = "/srv/got/проверка-${System.nanoTime()}.mkv"

        val saved = newEpisode(movie, path, keyframes)
        val loaded = episodeStore.find(saved.id!!)

        assertNotNull(loaded)
        assertEquals(11_081, loaded.keyframeMap?.byteLength)
        assertTrue(loaded.keyframeMap!!.isKeyframe(0))
        assertTrue(loaded.keyframeMap!!.isKeyframe(240))
        assertTrue(loaded.keyframeMap!!.isKeyframe(88_642))
        assertEquals(3, loaded.keyframeMap!!.keyframeCount())
        assertContentEquals(keyframes.toByteArray(), loaded.keyframeMap!!.toByteArray())
    }

    @Test
    fun `параметры серии возвращаются из базы без изменений`() {
        val movie = newMovie("/srv/got")
        val saved =
            newEpisode(
                movie,
                "/srv/got/параметры-${System.nanoTime()}.mkv",
                KeyframeMap.build(frameCount = 88_643, keyframes = listOf(0, 1000)),
            )

        val loaded = episodeStore.find(saved.id!!)!!

        assertEquals(88_643, loaded.frameCount)
        assertEquals(1001, loaded.timeBaseNum)
        assertEquals(24_000, loaded.timeBaseDen)
        assertEquals(1920, loaded.width)
        assertEquals(1080, loaded.height)
        assertEquals(5_598_286_865L, loaded.byteSize)
        assertEquals("h264", loaded.videoCodec)
        assertEquals("High", loaded.videoProfile)
        assertEquals("yuv420p", loaded.pixelFormat)
        assertEquals("ac3", loaded.audioCodec)
        assertEquals(6, loaded.audioChannels)
        assertEquals(48_000, loaded.audioSampleRate)
        assertEquals(3697.15, loaded.durationSeconds(), 0.01)
    }

    @Test
    fun `одна серия принадлежит ровно одному сериалу, повторный путь отвергается`() {
        val movie = newMovie("/srv/got")
        val path = "/srv/got/единственный-${System.nanoTime()}.mkv"
        val saved = newEpisode(movie, path, KeyframeMap.build(frameCount = 100, keyframes = listOf(0)))

        assertEquals(movie.id, saved.movieId)

        val failure =
            assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
                newEpisode(newMovie("/srv/got"), path, KeyframeMap.build(frameCount = 100, keyframes = listOf(0)))
            }

        assertEquals(ru.svoemesto.syp.core.contract.ErrorCode.CONFLICT, failure.code)
        assertTrue(failure.toBody().message.contains(path))
    }

    @Test
    fun `незарегистрированная серия читается пустой, а не выдуманной`() {
        assertNull(movies.find(-1))
        assertNull(episodeStore.find(-1))
    }

    @Test
    fun `удаление сериала каскадом уносит производные данные`() {
        val movie = newMovie("/srv/got")
        val saved =
            newEpisode(
                movie,
                "/srv/got/каскад-${System.nanoTime()}.mkv",
                KeyframeMap.build(frameCount = 200, keyframes = listOf(0, 100)),
            )
        locations.add(movie.id!!, "Лагерь ${System.nanoTime()}")
        val settingsBefore =
            db.selectOne(
                "SELECT count(*) AS total FROM tbl_analysis_settings WHERE id_movie = ?",
                { it.int("total") },
                movie.id,
            )

        assertEquals(11, settingsBefore)

        assertTrue(movies.delete(movie.id!!))

        assertNull(movies.find(movie.id!!))
        assertNull(episodeStore.find(saved.id!!))
        assertEquals(
            0,
            db.selectOne(
                "SELECT count(*) AS total FROM tbl_locations WHERE id_movie = ?",
                { it.int("total") },
                movie.id,
            ),
        )
        assertEquals(
            0,
            db.selectOne(
                "SELECT count(*) AS total FROM tbl_analysis_settings WHERE id_movie = ?",
                { it.int("total") },
                movie.id,
            ),
        )
    }

    @Test
    fun `справочник мест действия уникален в пределах сериала`() {
        val movie = newMovie("/srv/got")
        val name = "Весёлая Роджеровка ${System.nanoTime()}"
        val added = locations.add(movie.id!!, name)

        assertNotNull(added.id)
        assertContentEquals(listOf(name), locations.listByMovie(movie.id!!).map { it.name })

        val failure =
            assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
                locations.add(movie.id!!, name)
            }

        assertEquals(ru.svoemesto.syp.core.contract.ErrorCode.CONFLICT, failure.code)
    }

    @Test
    fun `список сериалов показывает число серий`() {
        val movie = newMovie("/srv/got")
        newEpisode(movie, "/srv/got/список-${System.nanoTime()}.mkv", KeyframeMap.build(frameCount = 50, keyframes = listOf(0)))

        val summary = movies.listWithEpisodeCount().first { it.movie.id == movie.id }

        assertEquals(1, summary.episodeCount)
        assertEquals(1, movies.countEpisode(movie.id!!))
    }

    @Test
    fun `следующий порядковый номер серии продолжает нумерацию`() {
        val movie = newMovie("/srv/got")

        assertEquals(0, movies.nextEpisodeOrdinal(movie.id!!))
        newEpisode(movie, "/srv/got/нумерация-${System.nanoTime()}.mkv", KeyframeMap.build(frameCount = 10, keyframes = listOf(0)))
        assertEquals(1, movies.nextEpisodeOrdinal(movie.id!!))
    }

    @Test
    fun `относительный путь вычисляется от корня сериала`() {
        val saved =
            newEpisode(
                newMovie("/disks/HDD_16Tb_Clouds/GOT"),
                "/disks/HDD_16Tb_Clouds/GOT/GOT.S01/GOT.S01E01.mkv",
                KeyframeMap.build(frameCount = 100, keyframes = listOf(0)),
            )

        assertEquals("GOT.S01/GOT.S01E01.mkv", saved.relativePath("/disks/HDD_16Tb_Clouds/GOT"))
        assertNull(saved.relativePath("/srv/got"))
    }

    @Test
    fun `серия без ключевых кадров записывается с пустой картой`() {
        val movie = newMovie("/srv/got")
        val saved =
            episodeStore.insert(
                Episode(
                    movieId = movie.id!!,
                    ordinal = 3,
                    name = "Без карты",
                    sourcePath = "/srv/got/без-карты-${System.nanoTime()}.mkv",
                    byteSize = 10,
                    fileMtime = java.time.OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                    frameCount = 100,
                    timeBaseNum = 1,
                    timeBaseDen = 25,
                    width = 8,
                    height = 8,
                    durationNum = 4,
                    durationDen = 1,
                    videoCodec = "h264",
                    videoProfile = null,
                    pixelFormat = "yuv420p",
                    keyframeMap = null,
                ),
            )

        assertNull(episodeStore.find(saved.id!!)?.keyframeMap)
    }

    @Test
    fun `карта неверной длины отвергается базой`() {
        val movie = newMovie("/srv/got")
        val path = "/srv/got/длина-карты-${System.nanoTime()}.mkv"
        val tooShort = KeyframeMap.build(frameCount = 100, keyframes = listOf(0)).toByteArray()

        val failure =
            assertFailsWith<ru.svoemesto.syp.core.db.DbException> {
                db.useTransaction { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, " +
                                "frame_count, time_base_num, time_base_den, width, height, duration_num, " +
                                "duration_den, video_codec, pixel_format, keyframe_bitmap) " +
                                "VALUES (?, 0, 'S01E01', ?, 10, now(), 100, 1, 25, 8, 8, 4, 1, 'h264', 'yuv420p', ?)",
                        ).use { statement ->
                            statement.setLong(1, movie.id!!)
                            statement.setString(2, path)
                            statement.setBytes(3, tooShort.copyOf(3))
                            statement.executeUpdate()
                        }
                }
            }

        assertTrue(
            failure.toString().contains("keyframe", ignoreCase = true),
            "отказ должен называть ограничение длины карты: ${failure.message}",
        )
    }
}
