package ru.svoemesto.syp.admin.catalog

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.ExternalProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки регистрации серии.
 *
 * Закрываются требования задач T033 и T034: путь вне корня сериала
 * отвергается с кодом `SOURCE_UNREADABLE` и внятным текстом, относительный
 * путь, который попал бы в сценарий, не может быть выдуман (FR-089a);
 * несуществующий или нечитаемый файл даёт `400` с кодом `SOURCE_UNREADABLE` и
 * путём в тексте, «успех с пустым результатом» невозможен (FR-092).
 *
 * Проверки пути не требуют ни базы, ни видео: путь проверяется до обращения к
 * базе и до опроса файла, поэтому отказ приходит именно там, где положено.
 * Проверка успешной регистрации требует базы и `ffmpeg`/`ffprobe`: без них она
 * помечается пропущенной.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeriesRegistrationTest {
    private lateinit var root: Path
    private lateinit var outside: Path
    private lateinit var serials: SerialStore
    private lateinit var seriesStore: SeriesStore
    private lateinit var pathChecks: SeriesRegistration
    private lateinit var serial: Serial

    /**
     * Готовит каталог сериала и хранилища домена.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun prepare() {
        val db: Db = TestDatabase.assumeDatabase()
        val workspace = Files.createTempDirectory("syp-registration")
        root = workspace.resolve("сериал")
        outside = workspace.resolve("снаружи")
        Files.createDirectories(root)
        Files.createDirectories(outside)

        serials = SerialStore(db)
        seriesStore = SeriesStore(db)
        pathChecks = registrationForPathChecks()
        serial = serials.create("Регистрация ${System.nanoTime()}", root.toString())
    }

    /**
     * Собирает регистрацию для проверок пути внутри корня.
     *
     * Путь к программе здесь не настоящий и не используется: проверка пути
     * выполняется до обращения к `ffprobe` — она и должна выполняться на
     * машине без видеоинструментов.
     *
     * @return регистрация серии
     */
    private fun registrationForPathChecks(): SeriesRegistration =
        SeriesRegistration(serials, seriesStore, SourceProbe(ExternalProgram(), "ffprobe"))

    /**
     * Собирает регистрацию серии, которой нужен настоящий `ffprobe`.
     *
     * Проверки пути внутри корня от видеоинструментов не зависят: путь
     * проверяется до обращения к программе, и на машине без `ffprobe` они
     * проходят, а не пропускаются. Требование выставляется здесь, чтобы
     * пропускались ровно те проверки, которым программа действительно нужна.
     *
     * @return регистрация серии
     * @throws org.opentest4j.TestAbortedException если программы нет
     */
    private fun registrationRequiringProbe(): SeriesRegistration {
        val ffprobe =
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.isNotBlank() }
                .map { Path.of(it, "ffprobe") }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()
        if (ffprobe == null) {
            throw org.opentest4j.TestAbortedException(
                "Программа ffprobe не найдена в PATH: проверки регистрации серии с опросом файла пропущены",
            )
        }
        return SeriesRegistration(serials, seriesStore, SourceProbe(ExternalProgram(), ffprobe))
    }

    /**
     * Готовит проверочный ролик внутри каталога.
     *
     * @param directory каталог, куда кладётся файл
     * @param name имя файла
     * @return путь к созданному файлу
     */
    private fun videoIn(
        directory: Path,
        name: String,
    ): Path {
        val ffmpeg =
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.isNotBlank() }
                .map { Path.of(it, "ffmpeg").toString() }
                .firstOrNull { Files.isExecutable(Path.of(it)) }
                ?: throw org.opentest4j.TestAbortedException("Программа ffmpeg не найдена в PATH")
        val target = directory.resolve(name)
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
    fun `путь вне корня сериала отвергается с кодом SOURCE_UNREADABLE`() {
        val outsideFile = videoIn(outside, "не-в-корне.mkv")

        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, outsideFile.toString())
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertEquals(400, failure.toBody().status)
        val text = failure.toBody().message
        assertTrue(text.contains(outsideFile.toString()), "текст должен называть путь: $text")
        assertTrue(text.contains(serial.sourceRoot), "текст должен называть корень сериала: $text")
    }

    @Test
    fun `путь в обход корня через два каталога отвергается`() {
        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, root.resolve("../снаружи/серия.mkv").toString())
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertTrue(failure.toBody().message.contains("выдуман"))
    }

    @Test
    fun `относительный путь отвергается`() {
        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, "серия.mkv")
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertTrue(failure.toBody().message.contains("абсолютным"))
    }

    @Test
    fun `пустой путь отвергается как непонятный запрос`() {
        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, "   ")
            }

        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
    }

    @Test
    fun `несуществующий файл внутри корня даёт SOURCE_UNREADABLE с путём`() {
        val missing = root.resolve("нет-такого-файла.mkv")

        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, missing.toString())
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertEquals(400, failure.toBody().status)
        assertTrue(failure.toBody().message.contains(missing.toString()))
    }

    @Test
    fun `символ внутри корня, уводящий наружу, отвергается`() {
        val link = root.resolve("наружу.mkv")
        if (link.exists()) link.deleteIfExists()
        val target = videoIn(outside, "цель-символа.mkv")
        Files.createSymbolicLink(link, target)

        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(serial, link.toString())
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertTrue(failure.toBody().message.contains("вне корня"))
    }

    @Test
    fun `недоступный корневой каталог сериала даёт внятный отказ`() {
        val missingRoot = serials.create("Нет корня ${System.nanoTime()}", root.resolve("нет-такого").toString())

        val failure =
            assertFailsWith<DomainException> {
                pathChecks.requireInsideRoot(missingRoot, root.resolve("что-угодно.mkv").toString())
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertTrue(failure.toBody().message.contains("корневой каталог"))
    }

    @Test
    fun `незаведённый сериал даёт NOT_FOUND, а не пустую серию`() {
        val failure =
            assertFailsWith<DomainException> {
                pathChecks.register(-1, root.resolve("что-угодно.mkv").toString())
            }

        assertEquals(ErrorCode.NOT_FOUND, failure.code)
        assertEquals(404, failure.toBody().status)
    }

    @Test
    fun `успешная регистрация снимает параметры с файла и пишет серию`() {
        val file = videoIn(root, "S01E01-проверка.mkv")

        val registered = registrationRequiringProbe().register(serial.id!!, file.toString())

        assertNotNull(registered.id)
        assertEquals("S01E01-проверка", registered.name)
        assertEquals(serial.id, registered.serialId)
        assertEquals(25, registered.frameCount)
        assertEquals(1, registered.timeBaseNum)
        assertEquals(25, registered.timeBaseDen)
        assertEquals(64, registered.width)
        assertEquals("S01E01-проверка.mkv", registered.relativePath(serial.sourceRoot))
        assertNotNull(registered.keyframeMap)
        assertTrue(registered.keyframeMap!!.isKeyframe(0))

        // Регистрация не оставила файлов вне своего каталога: система читает
        // источник и ничего в нём не меняет.
        assertTrue(file.exists())
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @Test
    fun `повторная регистрация того же файла даёт CONFLICT`() {
        val file = videoIn(root, "повтор-регистрации.mkv")
        val registering = registrationRequiringProbe()
        registering.register(serial.id!!, file.toString())

        val failure =
            assertFailsWith<DomainException> {
                registering.register(serial.id!!, file.toString())
            }

        assertEquals(ErrorCode.CONFLICT, failure.code)
        assertTrue(failure.toBody().message.contains("повтор-регистрации.mkv"))
    }
}
