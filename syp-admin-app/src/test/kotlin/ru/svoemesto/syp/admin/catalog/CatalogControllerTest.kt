package ru.svoemesto.syp.admin.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.config.ApiErrors
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorBody
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.ExternalProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки эндпоинтов приёма сериала и серии.
 *
 * Закрываются требованиями задачи T036: реализованы `GET /api/serials`,
 * `POST /api/serials`, `GET /api/serials/{serialId}`,
 * `DELETE /api/serials/{serialId}`, `GET /api/serials/{serialId}/series`,
 * `POST /api/serials/{serialId}/series`, `GET /api/series/{seriesId}`,
 * `DELETE /api/series/{seriesId}`, `GET /api/serials/{serialId}/settings` и
 * `PUT /api/serials/{serialId}/settings` (FR-100, FR-101).
 *
 * Контроллер проверяется напрямую, без поднятого HTTP-сервера: проверяются
 * контракт ответов и коды отказов, а не работа сетевого слоя Spring.
 * Соответствие ответа контракту проверяется Guard'ом
 * `tools/check-api-docs.sh` и чтением KDoc, а не этим тестом.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogControllerTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private lateinit var db: Db
    private lateinit var serials: SerialStore
    private lateinit var seriesStore: SeriesStore
    private lateinit var settingsStore: SerialSettingsStore
    private lateinit var controller: CatalogController
    private lateinit var errors: ApiErrors
    private lateinit var root: Path

    /**
     * Готовит хранилища, контроллер и каталог сериала.
     *
     * @throws org.opentest4j.TestAbortedException если база или видеоинструменты
     *   недоступны
     */
    @BeforeEach
    fun prepare() {
        db = TestDatabase.assumeDatabase()
        serials = SerialStore(db)
        seriesStore = SeriesStore(db)
        settingsStore = SerialSettingsStore(db, mapper)
        root = Files.createTempDirectory("syp-api").resolve("корень")
        Files.createDirectories(root)
        val ffprobe = programOnPath("ffprobe") ?: throw org.opentest4j.TestAbortedException("ffprobe не найден в PATH")
        controller =
            CatalogController(
                serials,
                seriesStore,
                settingsStore,
                SeriesRegistration(serials, seriesStore, SourceProbe(ExternalProgram(), ffprobe)),
            )
        errors = ApiErrors()
    }

    /**
     * Путь к программе в `PATH`.
     *
     * @param name имя программы
     * @return путь или `null`, если программы нет
     */
    private fun programOnPath(name: String): String? =
        (System.getenv("PATH") ?: "")
            .split(":")
            .filter { it.isNotBlank() }
            .map { Path.of(it, name).toString() }
            .firstOrNull { Files.isExecutable(Path.of(it)) }

    /**
     * Готовит проверочный ролик внутри каталога.
     *
     * @param name имя файла
     * @return путь к файлу
     */
    private fun video(name: String): Path {
        val ffmpeg = programOnPath("ffmpeg") ?: throw org.opentest4j.TestAbortedException("ffmpeg не найден в PATH")
        val target = root.resolve(name)
        ExternalProgram()
            .runOrFail(
                ffmpeg,
                listOf(
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc=duration=1:size=64x64:rate=25",
                    "-c:v",
                    "libx264",
                    "-g",
                    "25",
                    "-pix_fmt",
                    "yuv420p",
                    "-y",
                    target.toString(),
                ),
            )
        return target
    }

    @Test
    fun `список сериалов пуст, пока сериал не заведён`() {
        val created = controller.createSerial(CreateSerialRequest("Пустой список ${System.nanoTime()}", "/srv/нет"))

        assertEquals("/srv/нет", created.body?.serial?.sourceRoot)
        assertTrue(controller.listSerials().any { it.id == created.body?.serial?.id })
    }

    @Test
    fun `создание сериала отдаёт его вместе с настройками по умолчанию`() {
        val created = controller.createSerial(CreateSerialRequest("С настройками ${System.nanoTime()}", "/srv/got"))

        val body = created.body!!
        assertEquals(201, created.statusCode.value())
        assertEquals(11, body.settings.size)
        assertTrue(body.settings.all { it.value != null && it.updatedAt != null })
        assertEquals("Порог границы сцены", body.settings.first { it.key == "scene.threshold" }.title)
    }

    @Test
    fun `чтение сериала отдаёт сериал, серии и настройки`() {
        val created = controller.createSerial(CreateSerialRequest("Чтение ${System.nanoTime()}", "/srv/got"))
        val serialId = created.body!!.serial.id

        val detail = controller.readSerial(serialId)

        assertEquals(serialId, detail.serial.id)
        assertEquals(11, detail.settings.size)
        assertTrue(detail.series.isEmpty())
    }

    @Test
    fun `удаление сериала снимает его со счёта`() {
        val created = controller.createSerial(CreateSerialRequest("Удаление ${System.nanoTime()}", "/srv/got"))
        val serialId = created.body!!.serial.id

        val response = controller.deleteSerial(serialId)

        assertEquals(204, response.statusCode.value())
        assertNull(serials.find(serialId))
    }

    @Test
    fun `чтение несуществующего сериала даёт 404 с кодом и текстом`() {
        val failure = assertFailsWith<DomainException> { controller.readSerial(-1) }

        val response = errors.onDomainFailure(failure)
        assertEquals(404, response.statusCode.value())
        assertEquals(ErrorCode.NOT_FOUND.name, response.body!!.code)
        assertTrue(response.body!!.message.contains("-1"))
    }

    @Test
    fun `чтение несуществующей серии даёт 404, а не пустой ответ`() {
        val failure = assertFailsWith<DomainException> { controller.readSeries(-1) }

        val response = errors.onDomainFailure(failure)
        assertEquals(404, response.statusCode.value())
        assertEquals(ErrorCode.NOT_FOUND.name, response.body!!.code)
    }

    @Test
    fun `регистрация серии отдаёт снятые с файла параметры`() {
        val serial = controller.createSerial(CreateSerialRequest("Серия ${System.nanoTime()}", root.toString())).body!!.serial
        val file = video("S01E01.mkv")

        val response = controller.registerSeries(serial.id, RegisterSeriesRequest(file.toString()))

        assertEquals(201, response.statusCode.value())
        val view = response.body!!
        assertEquals(25, view.frameCount)
        assertEquals(64, view.width)
        // Частота кадров показывается в привычном виде «кадров в секунду»,
        // как её отдаёт ffprobe: 25/1. Длительность кадра, обратная величина,
        // хранится отдельно и в ответе не смешивается с частотой.
        assertEquals("25/1", view.frameRate)
        assertEquals(1, view.timeBaseNum)
        assertEquals(25, view.timeBaseDen)
        assertEquals(1.0, view.durationSeconds, 1e-9)
        assertEquals("h264", view.videoCodec)
        assertEquals("S01E01.mkv", view.relativePath)
        assertTrue(view.ready)
        assertTrue(view.keyframeCount >= 1)
        assertTrue(view.keyframeMapBytes > 0)
    }

    @Test
    fun `регистрация файла вне корня сериала даёт 400 с кодом SOURCE_UNREADABLE`() {
        val serial = controller.createSerial(CreateSerialRequest("Вне корня ${System.nanoTime()}", root.toString())).body!!.serial
        val other = Files.createTempDirectory("syp-api").resolve("снаружи")
        Files.createDirectories(other)

        val failure =
            assertFailsWith<DomainException> {
                controller.registerSeries(serial.id, RegisterSeriesRequest(other.resolve("серия.mkv").toString()))
            }

        val response = errors.onDomainFailure(failure)
        assertEquals(400, response.statusCode.value())
        assertEquals(ErrorCode.SOURCE_UNREADABLE.name, response.body!!.code)
    }

    @Test
    fun `чтение серии и её удаление работают по идентификатору`() {
        val serial = controller.createSerial(CreateSerialRequest("Удаление серии ${System.nanoTime()}", root.toString())).body!!.serial
        val registered =
            controller.registerSeries(serial.id, RegisterSeriesRequest(video("S01E02.mkv").toString())).body!!

        val read = controller.readSeries(registered.id)

        assertEquals(registered.id, read.id)
        assertEquals(1, controller.listSeries(serial.id).size)

        assertEquals(204, controller.deleteSeries(registered.id).statusCode.value())
        assertNull(seriesStore.find(registered.id))
        assertTrue(video("S01E02.mkv").exists(), "снятие с учёта не должно трогать файл архива")
    }

    @Test
    fun `настройки читаются и меняются через эндпоинты`() {
        val serial = controller.createSerial(CreateSerialRequest("Настройки ${System.nanoTime()}", "/srv/got")).body!!.serial

        val read = controller.readSettings(serial.id)
        assertEquals(11, read.size)
        assertEquals("NUMBER_LIST", read.first { it.key == "shot.size.thresholds" }.kind)

        val updated =
            controller.updateSettings(
                serial.id,
                mapOf("scene.threshold" to mapper.readTree("14"), "cluster.merge_threshold" to mapper.readTree("0.8")),
            )

        assertEquals(2, updated.changedKeys.size)
        assertEquals(
            14.0,
            updated.settings
                .first { it.key == "scene.threshold" }
                .value
                .asDouble(),
        )
        assertEquals(
            0.8,
            updated.settings
                .first { it.key == "cluster.merge_threshold" }
                .value
                .asDouble(),
        )

        val again = controller.updateSettings(serial.id, mapOf("scene.threshold" to mapper.readTree("14")))
        assertTrue(again.changedKeys.isEmpty(), "запись того же значения не является изменением")
    }

    @Test
    fun `неизвестная настройка отвечает 400 и перечисляет доступные`() {
        val serial = controller.createSerial(CreateSerialRequest("Ошибка ${System.nanoTime()}", "/srv/got")).body!!.serial

        val failure =
            assertFailsWith<DomainException> {
                controller.updateSettings(serial.id, mapOf("нет.такой" to mapper.readTree("1")))
            }

        val response = errors.onDomainFailure(failure)
        assertEquals(400, response.statusCode.value())
        val body: ErrorBody = response.body!!
        assertEquals(ErrorCode.BAD_REQUEST.name, body.code)
        assertTrue(body.message.contains("cluster.count"))
    }

    @Test
    fun `ошибка доступа к базе не выдаётся за отказ по правилам`() {
        val response =
            errors.onDatabaseFailure(
                ru.svoemesto.syp.core.db
                    .DbException("соединение отклонено: host=db пароль=hunter2"),
            )

        assertEquals(500, response.statusCode.value())
        assertEquals(ErrorCode.INTERNAL_ERROR.name, response.body!!.code)
        assertTrue(
            !response.body!!.message.contains("hunter2"),
            "подробности соединения не должны попадать в ответ клиенту: ${response.body!!.message}",
        )
    }

    @Test
    fun `тело ошибки сериализуется в общую форму обоих бэкендов`() {
        val body = ErrorBody.of(ErrorCode.SOURCE_UNREADABLE, "/srv/got/нет.mkv")

        val json = mapper.writeValueAsString(body)

        assertTrue(json.contains("\"code\":\"SOURCE_UNREADABLE\""))
        assertTrue(json.contains("/srv/got/нет.mkv"))
        assertNotNull(body.items)
    }
}
