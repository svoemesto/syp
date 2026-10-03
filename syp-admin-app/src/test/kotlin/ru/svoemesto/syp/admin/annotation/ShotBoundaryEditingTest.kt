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
import ru.svoemesto.syp.admin.analysis.Shot
import ru.svoemesto.syp.admin.analysis.ShotSize
import ru.svoemesto.syp.admin.analysis.SizeOrigin
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.ProjectSettingsStore
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.admin.characters.FacePlanBinding
import ru.svoemesto.syp.admin.characters.FaceStore
import ru.svoemesto.syp.admin.characters.PersonService
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки доводки границы плана оператором.
 *
 * Проверяются пять свойств, каждое из которых ломает структуру по-своему:
 *
 * 1. **Граница встаёт на границу плана или границу сцены.** Произвольный кадр
 *    отвергается: один план разрезает другой, а план лежит в сцене целиком
 *    (ADR-0007). Проверка идёт до записи, и отказ называет, между какими
 *    планами граница должна встать.
 * 2. **Принадлежность лиц пересчитывается.** После любой операции она совпадает
 *    с правилом диапазонов: лицо, оставшееся в поглощённой части плана, не
 *    числится в плане, которого для него уже нет (FR-034).
 * 3. **Размер пересчитывается по шкале.** План без лиц получает `NONE`, а план с
 *    лицом — ступень по доле площади рамки в площади кадра (ADR-0003). Размер,
 *    выбранный оператором вручную, пересчётом не затирается.
 * 4. **Покрытие не рвётся.** После любой операции планы покрывают эпизод без
 *    разрывов и перекрытий: дыра в покрытии выглядит на экране как «часть
 *    эпизода не показана» (FR-011).
 * 5. **Прежнее решение не пропадает.** Строка, выведенная из рабочей структуры,
 *    помечается устаревшим, а не удаляется (FR-093), а у затронутых планов
 *    `origin = OPERATOR` без ссылки на прогон.
 *
 * Проверки идут на уменьшенном эпизоде и требуют базы: без неё они
 * пропускаются штатным средством JUnit, а не проходят молча.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShotBoundaryEditingTest {
    private lateinit var db: Db
    private lateinit var structure: StructureService
    private lateinit var editing: ShotBoundaryEditing
    private lateinit var videofiles: VideofileStore
    private lateinit var persons: PersonService

    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        structure = StructureService(db, AnalysisRunStore(db), RawBoundaryStore(db))
        videofiles = VideofileStore(db)
        persons = PersonService(db)
        editing =
            ShotBoundaryEditing(
                db = db,
                structure = structure,
                binding = FacePlanBinding(db),
                videofiles = videofiles,
                settings = ProjectSettingsStore(db),
            )
    }

    /**
     * Заводит эпизод с готовой структурой: планы по 60 кадров, границы сцен —
     * 120, 300 и 480.
     *
     * Границы сцен — подмножество границ планов, как их и выдаёт автоматика:
     * правило структуры «сцена начинается планом» проверяется записью прогона,
     * и структура, где границы независимы, просто не создаётся. Благодаря
     * этому видно главное: план, начинающийся не с границы сцены, двигается
     * свободно, а граница, стоящая на границе сцены, — нет.
     *
     * @return эпизод с записанной структурой
     */
    private fun analysed(): Videofile {
        val frameCount = 600
        val token = System.nanoTime()
        val project = ProjectStore(db).create("Доводка плана $token", "/srv/got")
        val videofile =
            videofiles.insert(
                Videofile(
                    projectId = project.id!!,
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
                        videofileId = videofile.id!!,
                        kind = AnalysisKind.STRUCTURE,
                        algorithmVersion = DetectionResult.ALGORITHM_VERSION,
                        paramsHash = "e".repeat(64),
                    ),
                ).id!!
        structure.applyDetection(
            runId = run,
            videofileId = videofile.id,
            detection =
                DetectionResult(
                    sceneBoundaries = listOf(120, 300, 480),
                    shotBoundaries = (1 until frameCount / 60).map { it * 60 },
                    scores = frameCount,
                ),
        )
        return videofile
    }

    /**
     * Заводит лицо заданного размера в указанном кадре.
     *
     * @param videofileId эпизод
     * @param projectId фильм-владелец персоны
     * @param frame номер кадра
     * @param width ширина рамки в пикселях
     * @param height высота рамки в пикселях
     */
    private fun insertFace(
        videofileId: Long,
        projectId: Long,
        frame: Int,
        width: Int,
        height: Int,
    ) {
        val person = persons.create(projectId, "Персона $frame", "p$frame-${System.nanoTime()}")
        db.update(
            "INSERT INTO ${FaceStore.TABLE} " +
                "(id_videofile, frame_number, face_index, x1, y1, x2, y2, person_id, origin) " +
                "VALUES (?, ?, 0, 0, 0, ?, ?, ?, 'AUTO')",
            videofileId,
            frame,
            width,
            height,
            requireNotNull(person.id),
        )
    }

    /**
     * Рабочие планы эпизода по возрастанию первого кадра.
     *
     * @param videofileId эпизод
     * @return рабочие планы
     */
    private fun working(videofileId: Long): List<Shot> = structure.listShots(videofileId).filter { !it.isStale }.sortedBy { it.firstFrame }

    /**
     * Требует непрерывного покрытия участка планами.
     *
     * @param shots рабочие планы
     * @param firstFrame первый кадр участка
     * @param lastFrame последний кадр участка
     */
    private fun assertCovers(
        shots: List<Shot>,
        firstFrame: Int,
        lastFrame: Int,
    ) {
        val inside = shots.filter { it.lastFrame >= firstFrame && it.firstFrame <= lastFrame }
        assertTrue(inside.isNotEmpty(), "на участке $firstFrame…$lastFrame не осталось планов")
        assertEquals(firstFrame, inside.first().firstFrame, "покрытие начинается не с $firstFrame")
        assertEquals(lastFrame, inside.last().lastFrame, "покрытие кончается не на $lastFrame")
        inside.forEachIndexed { index, shot ->
            val next = inside.getOrNull(index + 1)
            if (next != null) {
                assertEquals(
                    shot.lastFrame + 1,
                    next.firstFrame,
                    "между планами ${shot.id} и ${next.id} есть разрыв или перекрытие",
                )
            }
        }
    }

    /**
     * План, которому принадлежит лицо в указанном кадре.
     *
     * @param videofileId эпизод
     * @param frame кадр с лицом
     * @return идентификатор плана либо `null`, если план не определён
     */
    private fun shotOfFace(
        videofileId: Long,
        frame: Int,
    ): Long? =
        db.selectOne(
            "SELECT shot_id FROM ${FaceStore.TABLE} WHERE id_videofile = ? AND frame_number = ?",
            { it.longOrNull("shot_id") },
            videofileId,
            frame,
        )

    @Test
    fun `разделение плана даёт два плана оператора и не рвёт покрытие`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        val before = working(videofileId)
        val source = before.first { it.firstFrame <= 330 && 330 <= it.lastFrame }
        assertEquals(300, source.firstFrame, "проверка ждёт план, начинающийся с кадра 300")

        val outcome = editing.splitShot(videofileId, 330)

        assertEquals(ShotBoundaryAction.SPLIT, outcome.action)
        assertEquals(2, outcome.affected.size, "после разделения на границе два плана")
        assertEquals(300, outcome.affected.first().firstFrame)
        assertEquals(329, outcome.affected.first().lastFrame)
        assertEquals(330, outcome.affected.last().firstFrame)
        assertEquals(source.lastFrame, outcome.affected.last().lastFrame)
        outcome.affected.forEach {
            assertEquals(BoundaryOrigin.OPERATOR, it.origin, "границу поставил оператор")
            assertEquals(null, it.runId, "у решения оператора нет породившего его прогона")
        }
        val after = working(videofileId)
        assertEquals(before.size + 1, after.size, "разделение добавляет один план")
        assertCovers(after, 0, videofile.frameCount - 1)
        assertTrue(
            structure.listShots(videofileId).any { it.id == source.id && it.isStale },
            "прежний план обязан остаться в базе помеченным устаревшим, а не исчезнуть",
        )
    }

    @Test
    fun `объединение возвращает прежние границы и оставляет прежний план устаревшим`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        val before = working(videofileId)
        val keeper = before.first { it.lastFrame == 359 }
        val absorbed = before.first { it.firstFrame == 360 }

        val outcome = editing.mergeShots(videofileId, 360)

        assertEquals(ShotBoundaryAction.MERGE, outcome.action)
        assertEquals(1, outcome.affected.size, "после объединения остаётся один план")
        assertEquals(keeper.firstFrame, outcome.affected.single().firstFrame)
        assertEquals(absorbed.lastFrame, outcome.affected.single().lastFrame)
        assertEquals(BoundaryOrigin.OPERATOR, outcome.affected.single().origin)
        val after = working(videofileId)
        assertEquals(before.size - 1, after.size, "объединение убирает один план из работы")
        assertCovers(after, 0, videofile.frameCount - 1)
        assertTrue(
            structure.listShots(videofileId).any { it.id == absorbed.id && it.isStale },
            "поглощённый план обязан остаться в базе помеченным устаревшим",
        )
    }

    @Test
    fun `сдвиг границы на кадр внутри плана отвергается`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        val left = working(videofileId).first { it.firstFrame == 240 }

        // 300 — начало сцены 300…479: сдвиг границы оттуда оставил бы сцену,
        // начинающуюся с середины плана 240…329.
        val failure =
            assertFailsWith<DomainException> {
                editing.moveShotBoundary(videofileId, fromFrame = 300, toFrame = 330)
            }

        assertEquals(ErrorCode.BOUNDARY_CONFLICT, failure.code)
        assertTrue(
            failure.message.orEmpty().contains("сцена обязана начинаться планом"),
            "отказ должен называть причину, а не отправлять оператора искать её: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("${left.firstFrame}…${left.lastFrame}"),
            "отказ должен называть, между какими планами граница должна встать: ${failure.message}",
        )
        assertCovers(working(videofileId), 0, videofile.frameCount - 1)
    }

    @Test
    fun `сдвиг границы плана переносит кадры между соседними планами`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        val before = working(videofileId)
        val second = before.first { it.firstFrame == 420 }

        // Граница 420 стоит внутри сцены 300…479: сдвинуть её можно в любой кадр
        // между границами соседних планов, и сцена по-прежнему начинается планом.
        val outcome = editing.moveShotBoundary(videofileId, fromFrame = 420, toFrame = 450)

        assertEquals(ShotBoundaryAction.MOVE, outcome.action)
        assertEquals(2, outcome.affected.size, "сдвиг меняет оба соседних плана")
        val byStart = outcome.affected.associateBy { it.firstFrame }
        assertEquals(449, byStart.getValue(360).lastFrame, "первый план отдал кадры второму")
        assertEquals(second.lastFrame, byStart.getValue(450).lastFrame, "вторый план сохранил конец")
        outcome.affected.forEach {
            assertEquals(BoundaryOrigin.OPERATOR, it.origin, "границу поставил оператор")
            assertEquals(null, it.runId, "у решения оператора нет породившего его прогона")
        }
        val after = working(videofileId)
        assertEquals(before.size, after.size, "сдвиг не меняет числа планов")
        assertCovers(after, 0, videofile.frameCount - 1)
    }

    @Test
    fun `после разделения лица следуют за планами, а не за прежними границами`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        insertFace(videofileId, videofile.projectId, frame = 250, width = 100, height = 100)
        insertFace(videofileId, videofile.projectId, frame = 345, width = 100, height = 100)

        editing.splitShot(videofileId, 330)

        val after = working(videofileId)
        val untouched = after.first { it.firstFrame == 240 }
        val tail = after.first { it.firstFrame == 330 }
        assertEquals(untouched.id, shotOfFace(videofileId, 250), "соседний план не тронут")
        assertEquals(tail.id, shotOfFace(videofileId, 345), "лицо второй части обязано уйти в новый план")
    }

    @Test
    fun `после объединения лицо из поглощённой части переходит в оставшийся план`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        insertFace(videofileId, videofile.projectId, frame = 250, width = 100, height = 100)
        insertFace(videofileId, videofile.projectId, frame = 320, width = 100, height = 100)
        insertFace(videofileId, videofile.projectId, frame = 345, width = 100, height = 100)
        editing.splitShot(videofileId, 330)
        val keeper = working(videofileId).first { it.firstFrame == 300 }
        val untouched = working(videofileId).first { it.firstFrame == 240 }

        editing.mergeShots(videofileId, 330)

        assertEquals(
            keeper.id,
            shotOfFace(videofileId, 320),
            "лицо оставшейся части обязано остаться в том же плане",
        )
        assertEquals(
            keeper.id,
            shotOfFace(videofileId, 345),
            "лицо поглощённой части обязано перейти в оставшийся план",
        )
        assertEquals(untouched.id, shotOfFace(videofileId, 250), "соседний план не тронут")
    }

    @Test
    fun `после разделения размеры планов пересчитаны по шкале`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        // Кадр 1920 на 1080 — площадь кадра 2 073 600.
        // Лицо 1100 на 900 = 990 000, доля 0,477: выше порога 0,35 — это ECU.
        // Лицо 250 на 250 = 62 500, доля 0,030: между 0,04 и 0,02 — это MLS.
        insertFace(videofileId, videofile.projectId, frame = 220, width = 1100, height = 900)
        insertFace(videofileId, videofile.projectId, frame = 200, width = 250, height = 250)

        val outcome = editing.splitShot(videofileId, 210)

        val byStart = outcome.affected.associateBy { it.firstFrame }
        assertEquals(ShotSize.MLS, byStart.getValue(180).size, "план с мелким лицом — MLS")
        assertEquals(ShotSize.ECU, byStart.getValue(210).size, "план с самым крупным лицом — ECU")
        val persisted = working(videofileId).associateBy { it.firstFrame }
        assertEquals(ShotSize.MLS, persisted.getValue(180).size, "пересчитанный размер обязан лежать в базе")
        assertEquals(ShotSize.ECU, persisted.getValue(210).size, "пересчитанный размер обязан лежать в базе")
    }

    @Test
    fun `план без лиц получает размер NONE`() {
        val videofile = analysed()

        val outcome = editing.mergeShots(videofileId = videofile.id!!, frame = 360)

        assertEquals(ShotSize.NONE, outcome.affected.single().size, "в плане нет лиц — размер не определён (ADR-0003)")
        assertEquals(SizeOrigin.AUTO, outcome.affected.single().sizeOrigin)
    }

    @Test
    fun `размер выбранный оператором пересчётом не затирается`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        insertFace(videofileId, videofile.projectId, frame = 150, width = 1100, height = 900)
        val source = working(videofileId).first { it.firstFrame == 120 }
        db.update(
            "UPDATE tbl_shots SET size = 'CU', size_origin = 'OPERATOR' WHERE id = ?",
            requireNotNull(source.id),
        )

        editing.splitShot(videofileId, 150)

        val head = working(videofileId).first { it.firstFrame == 120 }
        assertEquals(ShotSize.CU, head.size, "ручной размер оператора пересчёт затирать не должен")
        assertEquals(SizeOrigin.OPERATOR, head.sizeOrigin, "происхождение размера остаётся операторским")
    }

    @Test
    fun `разделение на первом кадре плана отвергается`() {
        val videofile = analysed()
        val videofileId = videofile.id!!

        val failure = assertFailsWith<DomainException> { editing.splitShot(videofileId, 300) }

        assertEquals(ErrorCode.BOUNDARY_CONFLICT, failure.code)
        assertCovers(working(videofileId), 0, videofile.frameCount - 1)
    }

    @Test
    fun `сдвиг за пределы второго плана отвергается`() {
        val videofile = analysed()
        val videofileId = videofile.id!!
        val second = working(videofileId).first { it.firstFrame == 300 }

        val failure =
            assertFailsWith<DomainException> {
                editing.moveShotBoundary(videofileId, fromFrame = 300, toFrame = second.lastFrame + 60)
            }

        assertEquals(ErrorCode.BOUNDARY_CONFLICT, failure.code)
        assertCovers(working(videofileId), 0, videofile.frameCount - 1)
    }

    @Test
    fun `размер плана без лиц после сдвига остаётся NONE`() {
        val videofile = analysed()
        val videofileId = videofile.id!!

        val outcome = editing.moveShotBoundary(videofileId, fromFrame = 420, toFrame = 450)

        val notNull = assertNotNull(outcome.affected.firstOrNull())
        assertEquals(ShotSize.NONE, notNull.size, "в плане нет лиц — размер не определён (ADR-0003)")
    }
}
