package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.core.db.Db
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки хранения лиц (задачи T064, T065, T067).
 *
 * Требования задач:
 *
 * 1. **T064** — у лица сохранены четыре координаты рамки, порядковый номер в
 *    кадре и уверенность детектора; естественный ключ — эпизод, номер кадра и
 *    порядковый номер; рамка перевёрнутая или вне разрешения — отказ базой;
 * 2. **T065** — принадлежность лица плану всегда соответствует правилу
 *    диапазонов кадров; пересчёт выполняется в той же транзакции, что и
 *    операция с границами; пустое значение допустимо, только если номер кадра
 *    вне диапазонов всех планов эпизода;
 * 3. **T067** — слишком вытянутая рамка не признаётся лицом и получает
 *    служебную персону «не лицо»; порог пропорций — настройка фильма.
 *
 * Проверки идут против живой базы: часть правил держит сама база, и на
 * подставной базе проверять было бы нечего.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaceStoreTest {
    private lateinit var db: Db
    private lateinit var projects: ProjectStore
    private lateinit var videofileStore: VideofileStore
    private lateinit var faces: FaceStore
    private lateinit var persons: PersonService
    private lateinit var binding: FacePlanBinding

    private val frameWidth = 1920
    private val frameHeight = 1080

    /**
     * Поднимает доступ к базе.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        projects = ProjectStore(db)
        videofileStore = VideofileStore(db)
        faces = FaceStore(db)
        persons = PersonService(db)
        binding = FacePlanBinding(db)
    }

    /**
     * Заводит эпизод в фильме.
     *
     * @param frames число кадров эпизода
     * @return записанный эпизод
     */
    private fun newVideofile(frames: Int = 1000): Videofile {
        val project = projects.create("Лица ${System.nanoTime()}", "/srv/got")
        return videofileStore.insert(
            Videofile(
                projectId = requireNotNull(project.id),
                ordinal = 0,
                name = "S01E0${System.nanoTime() % 10}",
                sourcePath = "/srv/got/лица-${System.nanoTime()}.mkv",
                byteSize = 1_073_741_824L,
                fileMtime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                frameCount = frames,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = frameWidth,
                height = frameHeight,
                durationNum = frames * 1001L,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                keyframeMap =
                    ru.svoemesto.syp.admin.catalog.KeyframeMap
                        .build(frames, listOf(0)),
            ),
        )
    }

    @Test
    fun `метка эталона ставится и снимается оператором`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized =
            requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        faces.saveFrame(
            videofileId = videofileId,
            frameNumber = 7,
            found = listOf(DetectedFace(x1 = 10, y1 = 10, x2 = 90, y2 = 90, confidence = 0.8)),
            personOf = { unrecognized },
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        val faceId = requireNotNull(faces.listByVideofile(videofileId).first { it.frameNumber == 7 }.id)

        assertFalse(
            requireNotNull(faces.find(faceId)).isExample,
            "новое лицо не должно начинать эталоном: метку ставит оператор, а не алгоритм",
        )

        val marked = faces.markExamples(listOf(faceId), isExample = true)
        assertEquals(1, marked, "пометка обязана затронуть лицо")
        assertTrue(requireNotNull(faces.find(faceId)).isExample, "метка эталона обязана сохраниться")

        val cleared = faces.markExamples(listOf(faceId), isExample = false)
        assertEquals(1, cleared, "снятие метки обязано затронуть лицо")
        assertFalse(requireNotNull(faces.find(faceId)).isExample, "метка обязана сниматься так же, как ставится")
    }

    @Test
    fun `пустой список лиц не помечает ничего`() {
        assertEquals(0, faces.markExamples(emptyList(), isExample = true), "помечать нечего — и вернуться нечему")
    }

    @Test
    fun `у лица сохранены рамка, порядковый номер и уверенность`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)

        faces.saveFrame(
            videofileId = videofileId,
            frameNumber = 42,
            found =
                listOf(
                    DetectedFace(x1 = 100, y1 = 120, x2 = 260, y2 = 280, confidence = 0.83),
                    DetectedFace(x1 = 700, y1 = 300, x2 = 860, y2 = 460, confidence = 0.51),
                ),
            personOf = { unrecognized },
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )

        val stored = faces.listByVideofile(videofileId).filter { it.frameNumber == 42 }
        assertEquals(2, stored.size, "в кадре записано два лица")
        val byIndex = stored.associateBy { it.faceIndex }
        val first = byIndex.getValue(0)
        assertEquals(100, first.x1)
        assertEquals(120, first.y1)
        assertEquals(260, first.x2)
        assertEquals(280, first.y2)
        assertEquals(0.83, first.detectConfidence!!, 1e-9, "уверенность детектора сохранена")
        assertEquals(FaceOrigin.AUTO, first.origin)
        assertEquals(1, byIndex.getValue(1).faceIndex, "порядковый номер лица в кадре сохранён")
    }

    @Test
    fun `естественный ключ лица — эпизод, кадр и порядковый номер`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        val found = listOf(DetectedFace(10, 10, 110, 110, 0.9))

        // Повторный проход идёт через replaceAutoFrame — так пишет задание
        // FACES: рамки прежнего прохода того же эпизода обновляются, а не
        // плодят вторые строки.
        faces.replaceAutoFrame(videofileId, 7, found, { unrecognized }, frameWidth, frameHeight)
        faces.replaceAutoFrame(videofileId, 7, found, { unrecognized }, frameWidth, frameHeight)

        assertEquals(
            1,
            faces.listByVideofile(videofileId).count { it.frameNumber == 7 },
            "повторная запись того же кадра не плодит второе лицо: работает естественный ключ",
        )
    }

    @Test
    fun `база отвергает перевёрнутую рамку`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)

        val failure =
            assertFailsWith<Exception> {
                db.update(
                    "INSERT INTO ${FaceStore.TABLE} (id_videofile, frame_number, face_index, x1, y1, x2, y2, " +
                        "person_id, origin) VALUES (?, 3, 0, 200, 10, 100, 100, ?, 'AUTO')",
                    videofileId,
                    unrecognized,
                )
            }
        assertTrue(
            failure.message?.contains("face_box_order") == true ||
                failure.message?.contains("перевёрнут") == true,
            "база обязана отвергнуть перевёрнутую рамку, а отвечает: ${failure.message}",
        )
    }

    @Test
    fun `база отвергает рамку за пределами разрешения`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)

        val failure =
            assertFailsWith<Exception> {
                db.update(
                    "INSERT INTO ${FaceStore.TABLE} (id_videofile, frame_number, face_index, x1, y1, x2, y2, " +
                        "person_id, origin) VALUES (?, 5, 0, 10, 10, 5000, 100, ?, 'AUTO')",
                    videofileId,
                    unrecognized,
                )
            }
        assertTrue(
            failure.message?.contains("выходит за пределы кадра") == true,
            "база обязана отвергнуть рамку за пределами кадра, а отвечает: ${failure.message}",
        )
        assertNull(
            faces.listByVideofile(videofileId).firstOrNull { it.frameNumber == 5 },
            "несохранённой рамки в базе быть не должно",
        )
    }

    @Test
    fun `принадлежность лица плану соответствует диапазонам кадров`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        val firstShot = insertShot(videofileId, 0, 99)
        val secondShot = insertShot(videofileId, 100, 199)

        faces.saveFrame(videofileId, 50, listOf(DetectedFace(10, 10, 110, 110, 0.9)), { unrecognized }, frameWidth, frameHeight)
        faces.saveFrame(videofileId, 150, listOf(DetectedFace(10, 10, 110, 110, 0.9)), { unrecognized }, frameWidth, frameHeight)
        faces.saveFrame(videofileId, 500, listOf(DetectedFace(10, 10, 110, 110, 0.9)), { unrecognized }, frameWidth, frameHeight)

        binding.rebindVideofile(videofileId)

        val stored = faces.listByVideofile(videofileId).associateBy { it.frameNumber }
        assertEquals(firstShot, stored.getValue(50).shotId, "кадр 50 лежит в первом плане")
        assertEquals(secondShot, stored.getValue(150).shotId, "кадр 150 лежит во втором плане")
        assertNull(stored.getValue(500).shotId, "кадр 500 вне всех планов — принадлежность пустая")
    }

    @Test
    fun `пересчёт отвязывает лицо от исчезнувшего плана`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        insertShot(videofileId, 0, 199)

        faces.saveFrame(videofileId, 50, listOf(DetectedFace(10, 10, 110, 110, 0.9)), { unrecognized }, frameWidth, frameHeight)
        binding.rebindVideofile(videofileId)
        assertNotNull(
            faces.listByVideofile(videofileId).first { it.frameNumber == 50 }.shotId,
            "до пересчёта кадр принадлежит плану",
        )

        db.update("UPDATE tbl_shots SET is_stale = TRUE WHERE id_videofile = ?", videofileId)
        binding.rebindVideofile(videofileId)

        assertNull(
            faces.listByVideofile(videofileId).first { it.frameNumber == 50 }.shotId,
            "план стал устаревшим — лицо обязано отвязаться от него",
        )
    }

    @Test
    fun `слишком вытянутая рамка получает служебную персону не лицо`() {
        val filter = NonPersonFilter(db, persons)
        val square = DetectedFace(100, 100, 200, 200, 0.9)
        val stretched = DetectedFace(0, 0, 800, 100, 0.9)

        assertEquals(1.0, filter.aspect(100, 100, 200, 200), 1e-9, "квадратная рамка имеет пропорцию 1")
        assertTrue(filter.looksLikeFace(square, 4.0), "квадратная рамка — лицо")
        assertTrue(!filter.looksLikeFace(stretched, 4.0), "рамка 800x100 при пороге 4 лицом не считается")
    }

    @Test
    fun `порог пропорций не зашит в код а берётся у фильма`() {
        val videofile = newVideofile()
        val settings =
            ru.svoemesto.syp.admin.catalog
                .ProjectSettingsStore(db)
                .read(videofile.projectId)
        val threshold = settings.number(ru.svoemesto.syp.admin.catalog.ProjectSetting.FACE_NOT_PERSON_ASPECT)
        assertTrue(
            threshold >= 1.0,
            "порог пропорций живёт в настройках фильма и не меньше единицы, задано $threshold",
        )
    }

    /**
     * Заводит план эпизода напрямую в базе.
     *
     * @param videofileId эпизод
     * @param first первый кадр
     * @param last последний кадр
     * @return идентификатор плана
     */
    private fun insertShot(
        videofileId: Long,
        first: Int,
        last: Int,
    ): Long {
        db.update(
            "INSERT INTO tbl_shots (id_videofile, first_frame, last_frame, size, size_origin, origin, is_stale) " +
                "VALUES (?, ?, ?, 'NONE', 'AUTO', 'AUTO', FALSE)",
            videofileId,
            first,
            last,
        )
        return requireNotNull(
            db.selectOne(
                "SELECT id FROM tbl_shots WHERE id_videofile = ? AND first_frame = ? AND last_frame = ?",
                { it.long("id") },
                videofileId,
                first,
                last,
            ),
        )
    }

    @Test
    fun `лица по перечню возвращаются по идентификаторам`() {
        val videofile = newVideofile()
        val videofileId = requireNotNull(videofile.id)
        val unrecognized =
            requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        faces.saveFrame(
            videofileId = videofileId,
            frameNumber = 7,
            found =
                listOf(
                    DetectedFace(x1 = 10, y1 = 20, x2 = 60, y2 = 90, confidence = 0.5),
                    DetectedFace(x1 = 100, y1 = 120, x2 = 200, y2 = 240, confidence = 0.6),
                ),
            personOf = { unrecognized },
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        val all = faces.listByVideofile(videofileId)
        assertEquals(2, all.size, "в кадре два лица")
        val wanted = listOf(requireNotNull(all[1].id), requireNotNull(all[0].id))

        val found = faces.listByIds(videofileId, wanted)

        assertEquals(
            listOf(all[0].id, all[1].id),
            found.map { it.id },
            "лица отданы по возрастанию идентификатора независимо от порядка перечня",
        )
        assertTrue(faces.listByIds(videofileId, emptyList()).isEmpty(), "пустой перечень даёт пустой список")
        assertTrue(
            faces.listByIds(requireNotNull(newVideofile().id), wanted).isEmpty(),
            "лица чужого эпизода не отдаются",
        )
    }
}
