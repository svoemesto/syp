package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.SerialStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.db.Db
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки эндпоинтов лиц, кластеров и персон (задача T071).
 *
 * Проверяются все шесть эндпоинтов из контракта
 * [`admin-api.md`](../../../../../../../specs/001-first-vertical-slice/contracts/admin-api.md)
 * § 5.5, и главное свойство удаления персоны: **лица не удаляются**, а
 * переходят в неопознанные (FR-036, SC-006).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CharactersControllerTest {
    private lateinit var db: Db
    private lateinit var serials: SerialStore
    private lateinit var seriesStore: SeriesStore
    private lateinit var settingsStore: SerialSettingsStore
    private lateinit var faces: FaceStore
    private lateinit var embeddings: FaceEmbeddingStore
    private lateinit var persons: PersonService
    private lateinit var controller: CharactersController

    private val frameWidth = 1920
    private val frameHeight = 1080
    private val embeddingModelKey = "test-embedding-1"

    /**
     * Поднимает доступ к базе.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        serials = SerialStore(db)
        seriesStore = SeriesStore(db)
        settingsStore = SerialSettingsStore(db)
        faces = FaceStore(db)
        embeddings = FaceEmbeddingStore(db)
        persons = PersonService(db)
    }

    /**
     * Собирает контроллер заново: он без состояния, но ключ модели эмбеддингов
     * в тестах один и тот же, а серии меняются от прогона к прогону.
     */
    @BeforeEach
    fun buildController() {
        controller =
            CharactersController(
                faces = faces,
                embeddings = embeddings,
                clustering = Clustering(),
                persons = persons,
                seriesStore = seriesStore,
                serials = serials,
                settingsStore = settingsStore,
                embeddingModelKey = embeddingModelKey,
            )
    }

    @Test
    fun `лица серии отдаются с рамкой, планом и персоной`() {
        val series = newSeries()
        val seriesId = requireNotNull(series.id)
        seedFaces(seriesId, series.serialId, 3)

        val view = controller.readFaces(seriesId, 0, 100)

        assertEquals(3, view.facesTotal, "у серии три лица")
        assertEquals(3, view.faces.size)
        val first = view.faces.first()
        assertEquals(10, first.x1)
        assertEquals(10, first.y1)
        assertEquals(110, first.x2)
        assertEquals(110, first.y2)
        assertEquals(PersonService.UNRECOGNIZED_NAME, first.personName, "безымянное лицо у служебной заглушки")
        assertEquals("AUTO", first.origin)
    }

    @Test
    fun `удаление персоны переводит её лица в неопознанных и не удаляет их`() {
        val series = newSeries()
        val seriesId = requireNotNull(series.id)
        seedFaces(seriesId, series.serialId, 2)
        val person =
            persons.create(
                serialId = series.serialId,
                name = "Джейми ${System.nanoTime() % 1000}",
                recognizerKey = "jamie-${System.nanoTime()}",
            )
        faces.assignPerson(requireNotNull(person.id), faces.listBySeries(seriesId).map { requireNotNull(it.id) })

        val before = faces.listBySeries(seriesId).size
        controller.deletePerson(requireNotNull(person.id))
        val after = controller.readFaces(seriesId, 0, 100)

        assertEquals(before, after.faces.size, "лица не удаляются: их столько же, сколько было")
        assertTrue(
            after.faces.all { it.personName == PersonService.UNRECOGNIZED_NAME },
            "после удаления персоны все её лица у служебной заглушки, а получили " +
                after.faces.map { it.personName }.distinct(),
        )
    }

    @Test
    fun `переименование персоны не ломает модель`() {
        val series = newSeries()
        val person = persons.create(series.serialId, "Джейми ${System.nanoTime() % 1000}", "jamie-key")
        val key = person.recognizerKey

        val renamed = controller.renamePerson(requireNotNull(person.id), RenamePersonRequest("Серена ${System.nanoTime() % 1000}"))

        assertEquals(key, renamed.recognizerKey, "ключ класса в модели переименование не трогает (ADR-0004)")
    }

    @Test
    fun `служебную персону переименовать и удалить нельзя`() {
        val series = newSeries()
        val service = persons.servicePerson(series.serialId, PersonKind.UNRECOGNIZED)

        assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
            controller.renamePerson(requireNotNull(service.id), RenamePersonRequest("Кто-то"))
        }
        assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
            controller.deletePerson(requireNotNull(service.id))
        }
    }

    @Test
    fun `кластеры серии строятся до появления обученной модели`() {
        val series = newSeries()
        val seriesId = requireNotNull(series.id)
        val seeded = seedFaces(seriesId, series.serialId, 4)
        // Четыре лица: три почти одного направления, одно перпендикулярное.
        embeddings.save(
            FaceEmbedding(seeded[0], embeddingModelKey, floatArrayOf(1f, 0f)),
        )
        embeddings.save(
            FaceEmbedding(seeded[1], embeddingModelKey, floatArrayOf(0.999f, 0.04f)),
        )
        embeddings.save(
            FaceEmbedding(seeded[2], embeddingModelKey, floatArrayOf(0.998f, -0.05f)),
        )
        embeddings.save(
            FaceEmbedding(seeded[3], embeddingModelKey, floatArrayOf(0f, 1f)),
        )

        val view = controller.readClusters(seriesId)

        assertEquals(
            2,
            view.clustersTotal,
            "до первого обучения лица группируются в кластеры по похожете векторов, получили " +
                view.clusters.map { it.faceIds },
        )
        assertTrue(view.clusters.all { it.size > 0 }, "в кластере есть хотя бы одно лицо")
        assertEquals(
            view.clusters.sumOf { it.size },
            4,
            "каждое лицо попало ровно в один кластер: не потеряно ни одно (FR-036)",
        )
    }

    @Test
    fun `дать кластеру имя заводит персону и назначает её лицам`() {
        val series = newSeries()
        val seriesId = requireNotNull(series.id)
        val seeded = seedFaces(seriesId, series.serialId, 3)
        embeddings.save(FaceEmbedding(seeded[0], embeddingModelKey, floatArrayOf(1f, 0f)))
        embeddings.save(FaceEmbedding(seeded[1], embeddingModelKey, floatArrayOf(0.999f, 0.02f)))
        embeddings.save(FaceEmbedding(seeded[2], embeddingModelKey, floatArrayOf(0f, 1f)))

        val cluster = controller.readClusters(seriesId).clusters.first()
        val response =
            controller.nameCluster(
                cluster.id,
                NameClusterRequest(name = "Брани ${System.nanoTime() % 1000}"),
            )

        assertEquals(cluster.size, response.facesAssigned, "лица кластера переведены этой персоне")
        assertEquals(cluster.id, response.recognizerKey, "ключ класса в модели — ключ кластера")
        val personsView = controller.readPersons(series.serialId)
        val named = personsView.persons.first { it.id == response.personId }
        assertEquals(response.name, named.name)
        assertTrue(!named.isService, "новый кластер стал именованной персоной")
    }

    @Test
    fun `названный кластер уходит из списка кластеров без имени`() {
        val series = newSeries()
        val seriesId = requireNotNull(series.id)
        val seeded = seedFaces(seriesId, series.serialId, 2)
        embeddings.save(FaceEmbedding(seeded[0], embeddingModelKey, floatArrayOf(1f, 0f)))
        embeddings.save(FaceEmbedding(seeded[1], embeddingModelKey, floatArrayOf(0.999f, 0.02f)))
        val cluster = controller.readClusters(seriesId).clusters.first()

        controller.nameCluster(cluster.id, NameClusterRequest(name = "Один ${System.nanoTime() % 1000}"))

        assertEquals(
            0,
            controller.readClusters(seriesId).clustersTotal,
            "кластер с именем стал персоной и в списке безымянных кластеров не остаётся",
        )
    }

    @Test
    fun `неразбираемый ключ кластера отвергается`() {
        assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
            controller.nameCluster("не-ключ", NameClusterRequest(name = "Кто-то"))
        }
    }

    @Test
    fun `несуществующая серия отвергается`() {
        assertFailsWith<ru.svoemesto.syp.core.contract.DomainException> {
            controller.readFaces(9_999_999_999, 0, 10)
        }
    }

    /**
     * Заводит серию в сериале.
     *
     * @return записанная серия
     */
    private fun newSeries(): Series {
        val serial = serials.create("Персоны ${System.nanoTime()}", "/srv/got")
        return seriesStore.insert(
            Series(
                serialId = requireNotNull(serial.id),
                ordinal = 0,
                name = "S01E0${System.nanoTime() % 10}",
                sourcePath = "/srv/got/лица-${System.nanoTime()}.mkv",
                byteSize = 1_073_741_824L,
                fileMtime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                frameCount = 1000,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = frameWidth,
                height = frameHeight,
                durationNum = 1001_000L,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                keyframeMap = KeyframeMap.build(1000, listOf(0)),
            ),
        )
    }

    /**
     * Заводит лица серии у служебной персоны «распознано, имя не подтверждено».
     *
     * @param seriesId серия
     * @param serialId сериал-владелец
     * @param count сколько лиц завести
     * @return идентификаторы заведённых лиц по возрастанию
     */
    private fun seedFaces(
        seriesId: Long,
        serialId: Long,
        count: Int,
    ): List<Long> {
        val unrecognized = requireNotNull(persons.servicePerson(serialId, PersonKind.UNRECOGNIZED).id)
        faces.saveFrame(
            seriesId = seriesId,
            frameNumber = 10,
            found = List(count) { index -> DetectedFace(10, 10, 110, 110, 0.9) },
            personOf = { unrecognized },
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        return faces.listBySeries(seriesId).map { requireNotNull(it.id) }
    }
}
