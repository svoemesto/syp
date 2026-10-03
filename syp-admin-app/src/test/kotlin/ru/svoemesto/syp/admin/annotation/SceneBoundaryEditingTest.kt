package ru.svoemesto.syp.admin.annotation

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.analysis.AnalysisKind
import ru.svoemesto.syp.admin.analysis.AnalysisRun
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.analysis.BoundaryOrigin
import ru.svoemesto.syp.admin.analysis.DetectionResult
import ru.svoemesto.syp.admin.analysis.RawBoundaryStore
import ru.svoemesto.syp.admin.analysis.Scene
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки доводки границы сцены оператором.
 *
 * Проверяются четыре свойства, каждое из которых ломает структуру по-своему:
 *
 * 1. **План не разрезается.** Граница сцены обязана попадать на границу плана,
 *    иначе в базе появляется сцена, часть плана которой лежит вне неё
 *    (ADR-0007). Нарушение отвергается, а не записывается.
 * 2. **Покрытие не рвётся.** После любой операции сцены покрывают тот же
 *    участок без разрывов и перекрытий: дыра в покрытии выглядит на экране
 *    как «часть эпизода не показана» (FR-011).
 * 3. **Граница принадлежит оператору.** У затронутых сцен `origin = OPERATOR`
 *    и нет ссылки на прогон: у решения человека нет породившего его прогона.
 * 4. **Прежнее решение не пропадает.** Строка, выведенная из рабочей
 *    структуры, помечается устаревшим, а не удаляется: иначе сравнить
 *    предложение машины с решением человека было бы нечем (FR-093).
 *
 * Проверки идут на уменьшенном эпизоде и требуют базы: без неё они
 * пропускаются штатным средством JUnit, а не проходят молча.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SceneBoundaryEditingTest {
    private lateinit var db: Db
    private lateinit var structure: StructureService
    private lateinit var editing: BoundaryEditing

    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        structure = StructureService(db, AnalysisRunStore(db), RawBoundaryStore(db))
        editing = BoundaryEditing(db, structure)
    }

    /**
     * Заводит эпизод с готовой структурой: планы по 60 кадров, сцены —
     * каждые две сцены планов.
     *
     * Каждый вызов заводит свой фильм, свой эпизод и свой путь к файлу: база
     * требует один эпизод на один файл, и общий путь у пяти проверок означал
     * бы, что первая из них занимает файл, а остальные падают мимо своего
     * предмета.
     *
     * @return эпизод с записанной структурой
     */
    private fun analysed(): Episode {
        val frameCount = 600
        val store = EpisodeStore(db)
        // Один уникальный признак на весь вызов, и имя фильма, имя эпизода и
        // путь к файлу берутся из него. Путь обязан быть уникальным: база
        // требует один эпизод на один файл, и проверка на одинаковый путь
        // стоит в `insert` до записи. С фиксированным путём первый же прогон
        // занимал файл, а остальные четыре падали с отказом `CONFLICT` — мимо
        // самой доводки границ, в заведении эпизода.
        val token = System.nanoTime()
        val movie = MovieStore(db).create("Доводка $token", "/srv/got")
        val episode =
            store.insert(
                Episode(
                    movieId = movie.id!!,
                    ordinal = 0,
                    name = "S1E1-$token.mkv",
                    sourcePath = "/srv/got/S1E1-$token.mkv",
                    byteSize = 1000,
                    fileMtime = java.time.OffsetDateTime.parse("2024-11-05T10:00:00Z"),
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
        val run =
            AnalysisRunStore(db)
                .begin(
                    AnalysisRun(
                        episodeId = episode.id!!,
                        kind = AnalysisKind.STRUCTURE,
                        algorithmVersion = DetectionResult.ALGORITHM_VERSION,
                        paramsHash = "d".repeat(64),
                    ),
                ).id!!
        val shotBoundaries = (1 until frameCount / 60).map { it * 60 }
        val sceneBoundaries = shotBoundaries.filter { it in listOf(120, 240, 360, 480, 540) }
        structure.applyDetection(
            runId = run,
            episodeId = episode.id,
            detection =
                DetectionResult(
                    sceneBoundaries = sceneBoundaries,
                    shotBoundaries = shotBoundaries,
                    scores = frameCount,
                ),
        )
        return episode
    }

    /**
     * Рабочие сцены эпизода: те, что не выведены из работы пометкой
     * устаревания.
     *
     * @param episodeId эпизод
     * @return рабочие сцены по возрастанию первого кадра
     */
    private fun working(episodeId: Long): List<Scene> = structure.listScenes(episodeId).filter { !it.isStale }.sortedBy { it.firstFrame }

    /**
     * Требует непрерывного покрытия участка сценами.
     *
     * @param scenes рабочие сцены
     * @param firstFrame первый кадр участка
     * @param lastFrame последний кадр участка
     */
    private fun assertCovers(
        scenes: List<Scene>,
        firstFrame: Int,
        lastFrame: Int,
    ) {
        val inside = scenes.filter { it.lastFrame >= firstFrame && it.firstFrame <= lastFrame }
        assertTrue(inside.isNotEmpty(), "на участке $firstFrame…$lastFrame не осталось сцен")
        assertEquals(firstFrame, inside.first().firstFrame, "покрытие начинается не с $firstFrame")
        assertEquals(lastFrame, inside.last().lastFrame, "покрытие кончается не на $lastFrame")
        inside.forEachIndexed { index, scene ->
            val next = inside.getOrNull(index + 1)
            if (next != null) {
                assertEquals(
                    scene.lastFrame + 1,
                    next.firstFrame,
                    "между сценами ${scene.id} и ${next.id} есть разрыв или перекрытие",
                )
            }
        }
    }

    @Test
    fun `разделение сцены даёт две сцены оператора и не рвёт покрытие`() {
        val episode = analysed()
        val episodeId = episode.id!!
        val before = working(episodeId)
        val source = before.first { it.firstFrame <= 300 && 300 <= it.lastFrame }
        assertEquals(240, source.firstFrame, "проверка ждёт сцену, начинающуюся с кадра 240")

        val outcome = editing.splitScene(episodeId, 300)

        assertEquals(SceneBoundaryAction.SPLIT, outcome.action)
        assertEquals(2, outcome.affected.size, "после разделения на границе две сцены")
        assertEquals(240, outcome.affected.first().firstFrame)
        assertEquals(299, outcome.affected.first().lastFrame)
        assertEquals(300, outcome.affected.last().firstFrame)
        assertEquals(source.lastFrame, outcome.affected.last().lastFrame)
        outcome.affected.forEach { scene ->
            assertEquals(BoundaryOrigin.OPERATOR, scene.origin, "границу поставил оператор")
            assertEquals(null, scene.runId, "у решения оператора нет породившего его прогона")
        }
        val after = working(episodeId)
        assertEquals(before.size + 1, after.size, "разделение добавляет одну сцену")
        assertCovers(after, 0, episode.frameCount - 1)
        assertTrue(
            structure.listScenes(episodeId).any { it.id == source.id && it.isStale },
            "прежняя сцена обязана остаться в базе помеченной устаревшей, а не исчезнуть",
        )
    }

    @Test
    fun `граница сцены не встаёт внутрь плана`() {
        val episode = analysed()
        val episodeId = episode.id!!
        val shots = structure.listShots(episodeId)
        val inside = shots.first { it.firstFrame <= 310 && 310 <= it.lastFrame }
        assertTrue(inside.firstFrame < 310, "проверке нужен кадр внутри плана, а не на его границе")

        val failure = assertFailsWith<DomainException> { editing.splitScene(episodeId, 310) }

        assertEquals(ErrorCode.BOUNDARY_CONFLICT, failure.code)
        assertTrue(
            failure.message.orEmpty().contains("внутри плана"),
            "отказ должен называть причину, а отправлять оператора искать её: ${failure.message}",
        )
        assertCovers(working(episodeId), 0, episode.frameCount - 1)
    }

    @Test
    fun `объединение возвращает прежние границы`() {
        val episode = analysed()
        val episodeId = episode.id!!
        val before = working(episodeId)
        val keeper = before.first { it.lastFrame == 239 }
        val absorbed = before.first { it.firstFrame == 240 }

        val outcome = editing.mergeScenes(episodeId, 240)

        assertEquals(SceneBoundaryAction.MERGE, outcome.action)
        assertEquals(1, outcome.affected.size, "после объединения остаётся одна сцена")
        assertEquals(keeper.firstFrame, outcome.affected.single().firstFrame)
        assertEquals(absorbed.lastFrame, outcome.affected.single().lastFrame)
        assertEquals(BoundaryOrigin.OPERATOR, outcome.affected.single().origin)
        val after = working(episodeId)
        assertEquals(before.size - 1, after.size, "объединение убирает одну сцену из работы")
        assertCovers(after, 0, episode.frameCount - 1)
        assertTrue(
            structure.listScenes(episodeId).any { it.id == absorbed.id && it.isStale },
            "поглощённая сцена обязана остаться в базе помеченной устаревшей",
        )
    }

    @Test
    fun `сдвиг границы переносит её на соседнюю границу плана`() {
        val episode = analysed()
        val episodeId = episode.id!!
        val before = working(episodeId)
        val keeper = before.first { it.lastFrame == 239 }
        val absorbed = before.first { it.firstFrame == 240 }
        assertEquals(120, keeper.firstFrame, "проверка ждёт сцену 120…239")

        val outcome = editing.moveSceneBoundary(episodeId, fromFrame = 240, toFrame = 180)

        assertEquals(SceneBoundaryAction.MOVE, outcome.action)
        assertEquals(2, outcome.affected.size, "сдвиг меняет обе соседние сцены")
        val byStart = outcome.affected.associateBy { it.firstFrame }
        assertEquals(179, byStart.getValue(120).lastFrame, "первая сцена отдала кадры второй")
        assertEquals(absorbed.lastFrame, byStart.getValue(180).lastFrame, "вторая сцена сохранила конец")
        outcome.affected.forEach { scene ->
            assertEquals(BoundaryOrigin.OPERATOR, scene.origin, "границу поставил оператор")
        }
        val after = working(episodeId)
        assertEquals(before.size, after.size, "сдвиг не меняет числа сцен")
        assertCovers(after, 0, episode.frameCount - 1)
        assertTrue(
            after.any { it.firstFrame == 120 && it.lastFrame == 179 },
            "сцена 120…239 обязана стать сценой 120…179",
        )
        assertTrue(
            after.any { it.firstFrame == 180 && it.lastFrame == 359 },
            "сцена 240…359 обязана стать сценой 180…359",
        )
    }

    @Test
    fun `сдвиг за пределы второй сцены отвергается`() {
        val episode = analysed()
        val episodeId = episode.id!!
        val second = working(episodeId).first { it.firstFrame == 240 }

        val failure =
            assertFailsWith<DomainException> {
                editing.moveSceneBoundary(episodeId, fromFrame = 240, toFrame = second.lastFrame + 60)
            }

        assertEquals(ErrorCode.BOUNDARY_CONFLICT, failure.code)
        assertCovers(working(episodeId), 0, episode.frameCount - 1)
    }

    @Test
    fun `разделение плана даёт два плана без разрыва`() {
        val episodeId = analysed().id!!
        val before = working(episodeId)
        val scene = before.first { it.lastFrame - it.firstFrame >= 100 }
        // Кадр берётся серединой ПЛАНА, а не сцены: сцена может лежать внутри
        // плана, и тогда её середина оказывается на краю плана — а на краю
        // делить нечего, и отказ был бы прав, а проверка врёт.
        val middle =
            structure
                .listShots(episodeId)
                .first {
                    it.firstFrame <= (scene.firstFrame + scene.lastFrame) / 2 &&
                        (scene.firstFrame + scene.lastFrame) / 2 <= it.lastFrame
                }.let { (it.firstFrame + it.lastFrame) / 2 }

        val outcome = editing.splitShot(episodeId, middle)

        assertEquals(SceneBoundaryAction.SPLIT, outcome.action)
        val afterShots = structure.listShots(episodeId)
        val covering = afterShots.filter { it.firstFrame <= middle && middle <= it.lastFrame }
        assertEquals(1, covering.size, "после разделения плана кадр $middle должен попасть ровно в один план")
        val all = afterShots.sortedBy { it.firstFrame }
        for ((left, right) in all.zipWithNext()) {
            assertEquals(
                left.lastFrame + 1,
                right.firstFrame,
                "после разделения плана разорван поток: ${left.firstFrame}…${left.lastFrame} и ${right.firstFrame}…${right.lastFrame}",
            )
        }
    }

    @Test
    fun `разделение плана на его краю отвергается`() {
        val episodeId = analysed().id!!
        val scene = working(episodeId).first()

        val failure =
            assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
                editing.splitShot(episodeId, scene.firstFrame)
            }

        assertEquals(
            ErrorCode.BOUNDARY_CONFLICT,
            failure.code,
            "на краю плана делить нечего, отказ должен называть конфликт границы: ${failure.message}",
        )
    }
}
