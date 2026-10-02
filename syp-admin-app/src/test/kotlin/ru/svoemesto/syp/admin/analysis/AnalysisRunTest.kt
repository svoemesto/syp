package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.db.Db
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки прогона анализа, сырых границ и рабочей структуры.
 *
 * Закрывают требования задач T046 и T048:
 *
 * 1. **повторный анализ создаёт новый прогон и новые сырые границы**, а
 *    предыдущие остаются — иначе невозможно ни сравнить «было и стало», ни
 *    сказать, какими входами получен результат (FR-090, FR-093);
 * 2. **сцены покрывают эпизод без разрывов и перекрытий**, и то же верно для
 *    планов (FR-011);
 * 3. **происхождение границы принимает ровно три значения**, а связь сцена и
 *    плана не хранится, а вычисляется по диапазонам кадров (ADR-0007).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisRunTest {
    private lateinit var db: Db
    private lateinit var episodeStore: EpisodeStore
    private lateinit var runStore: AnalysisRunStore
    private lateinit var boundaryStore: RawBoundaryStore
    private lateinit var structure: StructureService

    private companion object {
        /** Идентификатор прогона, которого заведомо нет: ссылка должна отклониться. */
        const val НЕСУЩЕСТВУЮЩИЙ_ПРОГОН: Long = 9_999_999_999L
    }

    /**
     * Поднимает доступ к базе и хранилища анализа.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        episodeStore = EpisodeStore(db)
        runStore = AnalysisRunStore(db)
        boundaryStore = RawBoundaryStore(db)
        structure = StructureService(db, runStore, boundaryStore)
    }

    /**
     * Заводит эпизод с указанным числом кадров.
     *
     * @param frameCount число кадров эпизода
     * @return записанный эпизод
     */
    private fun newEpisode(frameCount: Int): Episode {
        val movies = MovieStore(db)
        val movie = movies.create("Анализ ${System.nanoTime()}", "/srv/got")
        return episodeStore.insert(
            Episode(
                movieId = movie.id!!,
                ordinal = 0,
                name = "S1E1",
                sourcePath = "/srv/got/S1E1-${System.nanoTime()}.mkv",
                byteSize = 1000,
                fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                frameCount = frameCount,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = 1920,
                height = 1080,
                durationNum = frameCount.toLong() * 1001,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                keyframeMap = KeyframeMap.build(frameCount, listOf(0)),
            ),
        )
    }

    @Test
    fun `повторный анализ создаёт новый прогон и не трогает прежний`() {
        val episode = newEpisode(300)
        val first =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "a".repeat(64),
                ),
            )
        runStore.startWork(first.id!!)
        runStore.complete(first.id!!)
        boundaryStore.appendAll(
            listOf(
                RawBoundary(runId = first.id!!, level = BoundaryLevel.SCENE, firstFrame = 150, lastFrame = 150),
            ),
        )

        val second =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "b".repeat(64),
                ),
            )
        boundaryStore.appendAll(
            listOf(
                RawBoundary(runId = second.id!!, level = BoundaryLevel.SCENE, firstFrame = 100, lastFrame = 100),
            ),
        )

        val runs = runStore.listByEpisode(episode.id!!)
        assertEquals(2, runs.size, "оба прогона обязаны остаться: без прежнего нет сравнения")
        assertEquals(first.id, runs.last().id, "свежий прогон идёт первым")
        assertEquals(AnalysisState.DONE, runs.last { it.id == first.id }.state)
        assertEquals(1, boundaryStore.countByRun(first.id!!, BoundaryLevel.SCENE))
        assertEquals(1, boundaryStore.countByRun(second.id!!, BoundaryLevel.SCENE))

        // Смена параметров помечает прежний результат устаревшим, но не
        // удаляет его: ручные правки по старым границам должны остаться
        // видимыми (FR-090, SC-006).
        assertEquals(1, runStore.markStaleExcept(episode.id!!, AnalysisKind.STRUCTURE, "b".repeat(64)))
        val stale = runStore.find(first.id!!)
        assertTrue(stale!!.isStale)
        assertEquals(AnalysisState.DONE, stale.state, "устаревший прогон не перестаёт быть прогоном")
        assertEquals(1, boundaryStore.countByRun(first.id!!, BoundaryLevel.SCENE))
    }

    @Test
    fun `прогон в состоянии ошибки обязан нести текст`() {
        val episode = newEpisode(100)
        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.FACES,
                    algorithmVersion = "detector-1",
                    paramsHash = "c".repeat(64),
                ),
            )

        assertFailsWith<IllegalArgumentException> {
            AnalysisRun(
                episodeId = episode.id!!,
                kind = AnalysisKind.FACES,
                algorithmVersion = "detector-1",
                paramsHash = "d".repeat(64),
                state = AnalysisState.ERROR,
            )
        }

        runStore.fail(run.id!!, "детектор не ответил на кадре 12")
        val failed = runStore.find(run.id!!)
        assertEquals(AnalysisState.ERROR, failed?.state)
        assertEquals("детектор не ответил на кадре 12", failed?.errorText)
        assertNotNull(failed?.finishedAt)
    }

    @Test
    fun `сырая граница принадлежит ровно одному прогону`() {
        val episode = newEpisode(100)
        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "e".repeat(64),
                ),
            )
        boundaryStore.appendAll(
            listOf(
                RawBoundary(runId = run.id!!, level = BoundaryLevel.SCENE, firstFrame = 10, lastFrame = 10),
                RawBoundary(runId = run.id!!, level = BoundaryLevel.SHOT, firstFrame = 10, lastFrame = 10),
                RawBoundary(runId = run.id!!, level = BoundaryLevel.SHOT, firstFrame = 40, lastFrame = 40),
            ),
        )

        val scenes = boundaryStore.listByRun(run.id!!, BoundaryLevel.SCENE)
        val shots = boundaryStore.listByRun(run.id!!, BoundaryLevel.SHOT)

        assertEquals(1, scenes.size)
        assertEquals(2, shots.size)
        assertTrue(scenes.all { it.runId == run.id }, "граница принадлежит ровно одному прогону")

        db.use { connection ->
            assertTrue(runStore.listByEpisode(episode.id!!).all { it.kind == AnalysisKind.STRUCTURE })
            // Ссылка на прогон проверяется базой: граница без прогона
            // означала бы предложение алгоритма, у которого неизвестно, при
            // каких входах оно получено.
            assertFailsWith<java.sql.SQLException> {
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_raw_boundaries (run_id, level, first_frame, last_frame) VALUES (?, 'SCENE', 1, 1)",
                    ).use { statement ->
                        statement.setLong(1, НЕСУЩЕСТВУЮЩИЙ_ПРОГОН)
                        statement.executeUpdate()
                    }
            }
        }
    }

    @Test
    fun `сцены и планы покрывают эпизод без разрывов и перекрытий`() {
        val episode = newEpisode(1000)
        val detection =
            DetectionResult(
                sceneBoundaries = listOf(300, 700),
                shotBoundaries = listOf(100, 300, 500, 700, 900),
                scores = 12,
            )
        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "1".repeat(64),
                ),
            )

        val (sceneCount, shotCount) = structure.applyDetection(run.id!!, episode.id!!, detection)

        assertEquals(3, sceneCount, "две границы сцен делят эпизод на три сцены")
        assertEquals(6, shotCount, "пять границ планов делят эпизод на шесть планов")

        val scenes = structure.listScenes(episode.id!!)
        val shots = structure.listShots(episode.id!!)
        assertTrue(scenes.zipWithNext().all { (a, b) -> a.lastFrame + 1 == b.firstFrame }, "между сценами нет разрывов и перекрытий")
        assertTrue(shots.zipWithNext().all { (a, b) -> a.lastFrame + 1 == b.firstFrame }, "между планами нет разрывов и перекрытий")
        assertEquals(0, scenes.first().firstFrame)
        assertEquals(999, scenes.last().lastFrame)
        assertEquals(0, shots.first().firstFrame)
        assertEquals(999, shots.last().lastFrame)
        assertTrue(scenes.all { it.origin == BoundaryOrigin.AUTO })
        assertTrue(shots.all { it.size == ShotSize.NONE }, "план без лиц получает размер NONE (FR-042)")
    }

    @Test
    fun `связь сцена и плана вычисляется по диапазонам кадров`() {
        val episode = newEpisode(1000)
        val detection =
            DetectionResult(
                sceneBoundaries = listOf(400),
                shotBoundaries = listOf(200, 400, 800),
                scores = 9,
            )
        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "2".repeat(64),
                ),
            )
        structure.applyDetection(run.id!!, episode.id!!, detection)

        val scenes = structure.listScenes(episode.id!!)
        val shots = structure.listShots(episode.id!!)
        val firstScene = scenes.first { it.firstFrame == 0 }
        val secondScene = scenes.first { it.firstFrame == 400 }

        val inside = structure.shotsInside(firstScene, shots)
        assertTrue(
            inside.all { it.firstFrame >= firstScene.firstFrame && it.lastFrame <= firstScene.lastFrame },
            "внутри сцены лежат только планы, попавшие в её диапазон целиком",
        )
        assertTrue(
            structure.shotsInside(secondScene, shots).isNotEmpty(),
            "у второй сцены есть свои планы",
        )
        assertEquals(shots.size, scenes.sumOf { structure.shotsInside(it, shots).size }, "каждый план принадлежит ровно одной сцене")
    }

    @Test
    fun `происхождение границы принимает ровно три значения`() {
        val episode = newEpisode(100)
        val detection = DetectionResult(sceneBoundaries = emptyList(), shotBoundaries = emptyList(), scores = 0)
        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "3".repeat(64),
                ),
            )
        structure.applyDetection(run.id!!, episode.id!!, detection)

        val stored = structure.listScenes(episode.id!!).single()
        assertEquals(BoundaryOrigin.AUTO, stored.origin)
        assertEquals(
            listOf(BoundaryOrigin.AUTO, BoundaryOrigin.OPERATOR, BoundaryOrigin.CANCELLED),
            BoundaryOrigin.entries,
            "значений ровно три: алгоритм, оператор, отмена решения алгоритма",
        )

        db.use { connection ->
            assertFailsWith<java.sql.SQLException> {
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_scenes (id_episode, first_frame, last_frame, origin) " +
                            "VALUES (?, 0, 10, 'MAGIC')",
                    ).use { statement ->
                        statement.setLong(1, episode.id!!)
                        statement.executeUpdate()
                    }
            }
            assertFailsWith<java.sql.SQLException> {
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_scenes (id_episode, first_frame, last_frame, origin) " +
                            "VALUES (?, 20, 10, 'AUTO')",
                    ).use { statement ->
                        statement.setLong(1, episode.id!!)
                        statement.executeUpdate()
                    }
            }
        }
    }

    @Test
    fun `прежняя структура помечается устаревшей, а не удаляется`() {
        val episode = newEpisode(600)
        val firstRun =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "4".repeat(64),
                ),
            )
        structure.applyDetection(
            firstRun.id!!,
            episode.id!!,
            DetectionResult(listOf(200), listOf(200), scores = 4),
        )
        val secondRun =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = "ffmpeg-scdet-1",
                    paramsHash = "5".repeat(64),
                ),
            )
        structure.applyDetection(
            secondRun.id!!,
            episode.id!!,
            DetectionResult(listOf(300), listOf(300), scores = 4),
        )

        val scenes = structure.listScenes(episode.id!!)
        assertEquals(4, scenes.size, "прежняя структура сохранена, новая добавлена рядом")
        assertTrue(scenes.any { it.isStale }, "прежние сцены помечены устаревшими")
        assertTrue(scenes.any { !it.isStale }, "новые сцены актуальны")
        assertNotNull(scenes.first { it.firstFrame == 200 }.runId)
        assertFalse(scenes.first { it.firstFrame == 0 && !it.isStale }.isStale)
        assertNull(
            structure.listScenes(episode.id!!).firstOrNull { it.firstFrame == 999 },
            "структура покрывает эпизод целиком, а не выходит за её пределы",
        )
    }
}
