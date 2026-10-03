package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки служебных персон-заглушек (задача T069).
 *
 * Требования задачи:
 *
 * 1. **у каждого фильма есть обе персоны-заглушки** — «распознано, но имя не
 *    подтверждено» и «не лицо»;
 * 2. **удалению они не подлежат** — ни через сервис, ни прямым `DELETE`;
 * 3. **ссылка на персону у лица непустая всегда** — «нет персоны» выражается
 *    заглушкой, а пустой ссылкой нет: `face.person_id` объявлен `NOT NULL`,
 *    и удаление именованной персоны переводит её лица в неопознанных, а не
 *    стирает (FR-036, Р-12).
 *
 * Проверки идут против живой базы: заглушки заводит триггер базы, и на
 * подставной базе проверять было бы нечего.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonServiceTest {
    private lateinit var db: Db
    private lateinit var projects: ProjectStore
    private lateinit var videofileStore: VideofileStore
    private lateinit var persons: PersonService

    /**
     * Поднимает доступ к базе и сервис персон.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        projects = ProjectStore(db)
        videofileStore = VideofileStore(db)
        persons = PersonService(db)
    }

    @Test
    fun `у нового фильма сразу есть обе служебные персоны`() {
        val project = projects.create("Персоны ${System.nanoTime()}", "/srv/got")
        val service = persons.listByProject(project.id!!).filter { it.kind.isService }

        assertEquals(2, service.size, "у фильма обязаны быть обе служебные персоны")
        assertEquals(
            setOf(PersonKind.UNRECOGNIZED, PersonKind.NONPERSON),
            service.map { it.kind }.toSet(),
            "виды служебных персон: неопознанное лицо и «не лицо»",
        )
        assertTrue(
            service.all { it.recognizerKey == null },
            "у служебных персон ключа распознавателя быть не должно: это заглушки, а не классы модели",
        )
    }

    @Test
    fun `повторный вызов не заводит вторые заглушки`() {
        val project = projects.create("Повтор ${System.nanoTime()}", "/srv/got")
        val first = persons.ensureServicePersons(project.id!!)
        val second = persons.ensureServicePersons(project.id!!)

        assertEquals(
            first.map { it.id },
            second.map { it.id },
            "повторный вызов обязан вернуть те же персоны, а не создать новые",
        )
        assertEquals(
            2,
            persons.listByProject(project.id).count { it.kind.isService },
            "служебных персон у фильма должно быть ровно две",
        )
    }

    @Test
    fun `служебную персону удалить нельзя`() {
        val project = projects.create("Удаление ${System.nanoTime()}", "/srv/got")
        val unrecognized = persons.servicePerson(project.id!!, PersonKind.UNRECOGNIZED)

        val failure =
            assertFailsWith<DomainException> {
                persons.delete(requireNotNull(unrecognized.id))
            }

        assertEquals(ErrorCode.CONFLICT, failure.code, "отказ служебной персоны — конфликт, а не отказ базы")
        assertTrue(
            failure.message.orEmpty().contains("удалить нельзя"),
            "текст отказа должен объяснять, почему удаление невозможно. Получено: ${failure.message}",
        )
        assertNotNull(persons.find(requireNotNull(unrecognized.id)), "персона обязана остаться на месте")
    }

    @Test
    fun `служебную персону нельзя переименовать`() {
        val project = projects.create("Переименование ${System.nanoTime()}", "/srv/got")
        val nonPerson = persons.servicePerson(project.id!!, PersonKind.NONPERSON)

        val failure =
            assertFailsWith<DomainException> {
                persons.rename(requireNotNull(nonPerson.id), "Джейми Ланистер")
            }

        assertEquals(ErrorCode.CONFLICT, failure.code, "переименование служебной персоны — конфликт")
    }

    @Test
    fun `под именем заглушки нельзя завести именованную персону`() {
        val project = projects.create("Занятое имя ${System.nanoTime()}", "/srv/got")

        val failure =
            assertFailsWith<DomainException> {
                persons.create(project.id!!, PersonService.NON_PERSON_NAME, "jamie")
            }

        assertEquals(ErrorCode.CONFLICT, failure.code, "имя заглушки занято")
    }

    @Test
    fun `удаление именованной персоны переводит её лица в неопознанных`() {
        val project = projects.create("Лица персоны ${System.nanoTime()}", "/srv/got")
        val videofile = newVideofile(project.id!!)
        val jamie = persons.create(project.id, "Джейми", "jamie")
        val other = persons.create(project.id, "Серион", "seryon")
        insertFace(videofile.id!!, frame = 10, personId = requireNotNull(jamie.id))
        insertFace(videofile.id, frame = 20, personId = requireNotNull(other.id))

        assertTrue(persons.delete(requireNotNull(jamie.id)), "именованная персона удаляется")

        val faces =
            db.select(
                "SELECT id, person_id FROM tbl_faces WHERE id_videofile = ? ORDER BY frame_number",
                { it.long("id") to it.long("person_id") },
                videofile.id,
            )
        assertEquals(2, faces.size, "лица не должны пропасть вместе с персоной (FR-036)")
        val unrecognized = persons.servicePerson(project.id, PersonKind.UNRECOGNIZED)
        assertEquals(
            requireNotNull(unrecognized.id),
            faces[0].second,
            "лицо удалённой персоны обязано перейти в неопознанных, а не исчезнуть",
        )
        assertEquals(
            requireNotNull(other.id),
            faces[1].second,
            "лицо другой персоны не должно затрагиваться",
        )
        assertNotNull(persons.find(requireNotNull(other.id)), "вторая персона остаётся на месте")
    }

    @Test
    fun `у лица ссылка на персону всегда непустая`() {
        val project = projects.create("Пустая ссылка ${System.nanoTime()}", "/srv/got")
        val videofile = newVideofile(project.id!!)
        val unrecognized = persons.servicePerson(project.id, PersonKind.UNRECOGNIZED)
        insertFace(videofile.id!!, frame = 0, personId = requireNotNull(unrecognized.id))

        val failure =
            assertFailsWith<java.sql.SQLException> {
                db.update(
                    "UPDATE tbl_faces SET person_id = NULL WHERE id_videofile = ?",
                    videofile.id,
                )
            }

        assertTrue(
            failure.message.orEmpty().contains("person_id"),
            "база обязана отвергнуть пустую ссылку на персону: «нет персоны» — это заглушка (Р-12). " +
                "Получено: ${failure.message}",
        )
    }

    /**
     * Заводит эпизод в фильме.
     *
     * @param projectId идентификатор фильма
     * @return записанный эпизод
     */
    private fun newVideofile(projectId: Long): Videofile =
        videofileStore.insert(
            Videofile(
                projectId = projectId,
                ordinal = 0,
                name = "S01E0${System.nanoTime() % 10}",
                sourcePath = "/srv/got/лица-${System.nanoTime()}.mkv",
                byteSize = 1000,
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
                keyframeMap = KeyframeMap.build(100, listOf(0)),
            ),
        )

    /**
     * Заводит лицо, отнесённое к персонам.
     *
     * @param videofileId идентификатор эпизода
     * @param frame номер кадра
     * @param personId идентификатор персоны
     */
    private fun insertFace(
        videofileId: Long,
        frame: Int,
        personId: Long,
    ) {
        db.update(
            "INSERT INTO tbl_faces (id_videofile, frame_number, face_index, x1, y1, x2, y2, person_id, origin) " +
                "VALUES (?, ?, 0, 10, 10, 20, 20, ?, 'AUTO')",
            videofileId,
            frame,
            personId,
        )
    }
}
