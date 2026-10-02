package ru.svoemesto.syp.admin.selection

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.integrity.ChecksumRegistry
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.recipe.RecipeCatalog
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.signing.Canonicalizer
import ru.svoemesto.syp.core.signing.Signer
import ru.svoemesto.syp.core.signing.VerificationKey
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ArtifactState
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки выдачи сценария сборки в админском бэкенде.
 *
 * Закрывают задачи T136 (выдача), T124 (наличие актуальных сумм) и T131
 * (пометка устаревания при смене версии формата) по решению владельца
 * 2026-10-03 (ADR-0014): выдачу и подпись выполняет админский бэкенд, где живёт
 * закрытый ключ.
 *
 * Проверяется главное свойство выдачи: сценарий в состоянии `DONE` подписан
 * тем ключом, который в контейнере админки, и **его же** открытым ключом,
 * канонические байты лежат в артефакте в состоянии `READY`, а одна изменённая
 * подпись или один изменённый байт делают проверку неуспешной.
 *
 * Проверки требуют живой базы: без неё они помечаются пропущенными. База
 * поднимается одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecipeIssuanceTest {
    private lateinit var db: Db
    private lateinit var movies: MovieStore
    private lateinit var episodeStore: EpisodeStore
    private lateinit var checksums: ChecksumRegistry
    private lateinit var recipes: RecipeStore
    private lateinit var catalog: RecipeCatalog
    private lateinit var artifacts: ArtifactRegistry
    private lateinit var signer: Signer
    private lateinit var verification: VerificationKey
    private lateinit var storageRoot: Path
    private lateinit var clock: Clock

    /**
     * Поднимает доступ к базе, хранилище и пару ключей подписи.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        movies = MovieStore(db)
        episodeStore = EpisodeStore(db)
        checksums = ChecksumRegistry(db)
        recipes = RecipeStore(db)
        catalog = RecipeCatalog(db)
        storageRoot = Files.createTempDirectory("syp-recipe-artifacts")
        artifacts = ArtifactRegistry(db, FileSystemStorage(storageRoot))
        val pair = Signer.generateKeyPair()
        signer = Signer(TEST_KEY_ID, pair.private)
        verification = VerificationKey.of(TEST_KEY_ID, pair.public)
        clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun `выданный сценарий подписан ключом админского контейнера и лежит в готовом артефакте`() {
        val fixture = fixture(profile = "High")
        val issued = builder().issue("Джейми — выходы", fixture.sceneIds)

        assertEquals("DONE", issued.state.name, "выданный сценарий обязан быть в состоянии DONE")
        assertEquals(TEST_KEY_ID, issued.signingKeyId, "в ответе называется идентификатор ключа, а не сам ключ")
        assertNotNull(issued.signature) { "сценарий без подписи выдан быть не может (FR-089c)" }
        assertNotNull(issued.contentSha256)
        assertFalse(issued.isStale, "свежевыданный сценарий устаревшим не бывает")
        assertEquals(fixture.sceneIds.size, issued.itemCount)

        val artifactId = issued.artifactId!!
        val artifact = artifacts.find(artifactId)
        assertNotNull(artifact)
        assertEquals(ArtifactState.READY, artifact!!.state, "незавершённый файл готовым не считается (FR-091)")

        val bytes = artifacts.openReady(artifactId).use { it.readBytes() }
        assertEquals(issued.contentSha256, Canonicalizer.checksum(bytes), "сумма содержимого обязана совпадать с байтами артефакта")
        assertTrue(
            verification.verify(bytes, issued.signature!!),
            "подпись обязана проверяться открытым ключом самой пары: иначе ошибка канонизации не заметна",
        )
        val tampered = bytes.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        assertFalse(
            verification.verify(tampered, issued.signature!!),
            "изменение одного байта обязано ломать проверку подписи",
        )
    }

    @Test
    fun `фрагмент несёт снимок места действия и имён, а не ссылки`() {
        val fixture = fixture(profile = "High")
        val issued = builder().issue("Снимок", fixture.sceneIds)
        val items = recipes.items(issued.id!!)

        assertEquals("Лагерь Джейми", items[0].locationName, "место действия кладётся названием-снимком")
        assertEquals(
            setOf("Джейми Ланистер", "Санса Старк"),
            items[0].personNames.toSet(),
            "имена кладутся снимком: и сам набор, и порядок задаёт снимок",
        )
        assertEquals(items[0].personNames.sorted(), items[0].personNames, "имена идут по алфавиту")
        assertFalse(
            items[0].personNames.contains("Не лицо"),
            "служебная персона не является персонажем сцены (Р-12)",
        )
        assertEquals(fixture.relativePath, items[0].relativePath, "путь относителен корню фильма (FR-089a)")

        // Правка справочника после выдачи не «слепит» уже скачанный сценарий.
        db.update("UPDATE tbl_locations SET name = ? WHERE id = ?", "Лагерь Хуттон", fixture.locationId)
        db.update("UPDATE tbl_persons SET name = ? WHERE id_movie = ? AND name = ?", "Джейми Тарл", fixture.movieId, "Джейми Ланистер")
        val after = recipes.items(issued.id!!)
        assertEquals("Лагерь Джейми", after[0].locationName, "снимок не должен меняться вместе со справочником")
        assertTrue(after[0].personNames.contains("Джейми Ланистер"), "снимок имён не меняется вместе со справочником")
    }

    @Test
    fun `сценарий без актуальной суммы не выдаётся`() {
        val fixture = fixture(profile = "High", withChecksum = false)
        val failure =
            assertFailsWith<DomainException> {
                builder().issue("Без суммы", fixture.sceneIds)
            }
        assertEquals(ErrorCode.CHECKSUM_NOT_READY, failure.code)
        assertEquals(409, failure.code.httpStatus)
    }

    @Test
    fun `несовместимые эпизоды не выдаются с перечнем различающихся признаков`() {
        val fixture = fixture(profile = "High")
        val other = newEpisode(fixture.movieId, "S1E2", ordinal = 1, profile = "Main")
        val scene = newScene(other.id!!, 20, 60)
        // Актуальная сумма есть, но эпизода несовместимы по профилю видео.
        checksums.begin(other, null).let { entry ->
            checksums.complete(entry.id!!, digest(7), other.byteSize, other.fileMtime)
        }

        val failure =
            assertFailsWith<DomainException> {
                builder().issue("Разные эпизоды", fixture.sceneIds + scene)
            }
        assertEquals(ErrorCode.INCOMPATIBLE_EPISODE, failure.code)
        assertEquals(422, failure.code.httpStatus)
        assertTrue(failure.items.isNotEmpty(), "отказ перечисляет проблемные эпизода")
    }

    @Test
    fun `пустой выбор даёт EMPTY_SELECTION и ничего не записывает`() {
        val before = db.selectOne("SELECT count(*) AS total FROM tbl_build_recipes", { it.int("total") }) ?: 0
        val failure = assertFailsWith<DomainException> { builder().issue("Пусто", emptyList()) }
        assertEquals(ErrorCode.EMPTY_SELECTION, failure.code)
        assertEquals(400, failure.code.httpStatus)
        val after = db.selectOne("SELECT count(*) AS total FROM tbl_build_recipes", { it.int("total") }) ?: 0
        assertEquals(before, after, "пустой выбор не должен оставлять записи")
    }

    @Test
    fun `смена версии формата помечает прежние сценарии устаревшими, но не удаляет их`() {
        val fixture = fixture(profile = "High")
        val issued = builder().issue("До смены версии", fixture.sceneIds)

        val marked = catalog.markStaleOnSchemaChange(fixture.movieId, issued.schemaVersion + 1)
        assertEquals(1, marked, "сценарий прежней версии формата обязан быть помечен")

        val stored = recipes.find(issued.id!!)!!
        assertTrue(stored.isStale)
        assertNotNull(stored.signature, "пометка устаревания не отзывает подпись")
        assertEquals(TEST_KEY_ID, stored.signingKeyId, "ранее выданный сценарий остаётся проверяемым по своему ключу")
        assertNotNull(stored.artifactId)

        val freshness = catalog.freshness(stored, issued.schemaVersion + 1)
        assertTrue(freshness.isStale)
        assertNotNull(freshness.staleResultCode)
        assertEquals("STALE_RESULT", freshness.staleResultCode)
    }

    /** Генератор, собранный на тестовых часах: момент выдачи предсказуем. */
    private fun builder(): RecipeBuilder =
        RecipeBuilder(
            db = db,
            episodeStore = episodeStore,
            checksums = checksums,
            settingsStore = MovieSettingsStore(db),
            recipes = recipes,
            catalog = catalog,
            artifacts = artifacts,
            signer = signer,
            clock = clock,
        )

    /** Данные для одной выдачи: фильм, эпизод с суммой, сцена, локация, персона, лицо. */
    private fun fixture(
        profile: String,
        withChecksum: Boolean = true,
    ): RecipeFixture {
        val movie = movies.create("Выдача ${System.nanoTime()}", SOURCE_ROOT)
        val episode = newEpisode(movie.id!!, "S1E1", ordinal = 0, profile = profile)
        if (withChecksum) {
            checksums.begin(episode, null).let { entry ->
                checksums.complete(entry.id!!, digest(1), episode.byteSize, episode.fileMtime)
            }
        }
        val locationId = insertLocation(movie.id!!, "Лагерь Джейми")
        val jamieId = insertPerson(movie.id!!, "Джейми Ланистер")
        val sansaId = insertPerson(movie.id!!, "Санса Старк")
        insertFace(episode.id!!, 15, jamieId)
        insertFace(episode.id!!, 40, sansaId)
        val scene = newScene(episode.id!!, 10, 90)
        db.update("UPDATE tbl_scenes SET location_id = ? WHERE id = ?", locationId, scene)
        return RecipeFixture(
            movieId = movie.id!!,
            episodeId = episode.id!!,
            sceneIds = listOf(scene),
            locationId = locationId,
            relativePath = episode.relativePath(SOURCE_ROOT)!!,
        )
    }

    /** Создаёт эпизод с картой ключевых кадров. */
    private fun newEpisode(
        movieId: Long,
        name: String,
        ordinal: Int,
        profile: String,
    ): Episode =
        episodeStore.insert(
            Episode(
                movieId = movieId,
                ordinal = ordinal,
                name = name,
                // Путь уникален во всей базе: один эпизод на один файл, поэтому
                // повторный прогон набора проверок не должен натыкаться на
                // прежний ряд.
                sourcePath = SOURCE_ROOT + "/" + name + "-" + System.nanoTime() + ".mkv",
                byteSize = 4096,
                fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                frameCount = FRAME_COUNT,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = 1920,
                height = 1080,
                durationNum = 100_100,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = profile,
                pixelFormat = "yuv420p",
                keyframeMap = KeyframeMap.build(FRAME_COUNT, KEYFRAMES),
            ),
        )

    /** Вставляет сцену и отдаёт её идентификатор. */
    private fun newScene(
        episodeId: Long,
        firstFrame: Int,
        lastFrame: Int,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_scenes (id_episode, first_frame, last_frame, origin) " +
                        "VALUES (?, ?, ?, 'AUTO') RETURNING id",
                ).use { statement ->
                    statement.setLong(1, episodeId)
                    statement.setInt(2, firstFrame)
                    statement.setInt(3, lastFrame)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /** Вставляет место действия фильма. */
    private fun insertLocation(
        movieId: Long,
        name: String,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement("INSERT INTO tbl_locations (id_movie, name) VALUES (?, ?) RETURNING id")
                .use { statement ->
                    statement.setLong(1, movieId)
                    statement.setString(2, name)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /** Вставляет обычную персону фильма. */
    private fun insertPerson(
        movieId: Long,
        name: String,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_persons (id_movie, name, recognizer_key, kind) " +
                        "VALUES (?, ?, ?, 'PERSON') RETURNING id",
                ).use { statement ->
                    statement.setLong(1, movieId)
                    statement.setString(2, name)
                    statement.setString(3, name)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /** Вставляет лицо, опознанное как персона. */
    private fun insertFace(
        episodeId: Long,
        frameNumber: Int,
        personId: Long,
    ) {
        db.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_faces (id_episode, frame_number, face_index, x1, y1, x2, y2, " +
                        "person_id, origin) VALUES (?, ?, 0, 10, 10, 40, 40, ?, 'AUTO')",
                ).use { statement ->
                    statement.setLong(1, episodeId)
                    statement.setInt(2, frameNumber)
                    statement.setLong(3, personId)
                    statement.executeUpdate()
                }
        }
    }

    /** Сумма из 64 шестнадцатеричных символов: формат `sha256sum`. */
    private fun digest(seed: Int): String = "%064x".format(seed)

    /** Данные одной выдачи. */
    private data class RecipeFixture(
        val movieId: Long,
        val episodeId: Long,
        val sceneIds: List<Long>,
        val locationId: Long,
        val relativePath: String,
    )

    private companion object {
        /** Корень каталога фильма на тестовой машине. */
        const val SOURCE_ROOT: String = "/tmp/syp-test-movie"

        /** Идентификатор тестовой пары ключей. */
        const val TEST_KEY_ID: String = "syp-test-2026-10"

        /** Число кадров тестового эпизода. */
        const val FRAME_COUNT: Int = 100

        /** Ключевые кадры тестового эпизода: округление границ идёт к ним. */
        val KEYFRAMES: List<Int> = listOf(0, 25, 50, 75, 99)
    }
}
