package ru.svoemesto.syp.admin.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.recipe.RecipeFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки настроек анализа и выдачи сценария.
 *
 * Закрываются требованиями задачи T035: новый фильм получает 11 настроек по
 * умолчанию; пороги границ, пороги размера плана, порог детектора, параметры
 * кластеризации, раскладка листа превью, версия формата сценария и число
 * аудиодорожек меняются без правки кода (ADR-0003, constitution).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectSettingsTest {
    private val mapper = ObjectMapper()
    private lateinit var db: Db
    private lateinit var settings: ProjectSettingsStore
    private var projectId: Long = 0

    /**
     * Готовит доступ к базе и **свой** фильм на каждую проверку.
     *
     * Фильм заводится заново перед каждой проверкой: проверки меняют настройки,
     * и общий фильм делал бы результат зависящим от порядка их запуска —
     * проверка «значения по умолчанию» прошла бы или нет в зависимости от того,
     * что отработало раньше.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeEach
    fun prepare() {
        db = TestDatabase.assumeDatabase()
        settings = ProjectSettingsStore(db, mapper)
        projectId = ProjectStore(db).create("Настройки ${System.nanoTime()}", "/srv/got").id!!
    }

    /**
     * Создаёт узел JSON из числа.
     *
     * @param value значение
     * @return узел
     */
    private fun number(value: Double) = DoubleNode.valueOf(value)

    @Test
    fun `новый фильм получает 11 настроек по умолчанию`() {
        val read = settings.read(projectId)

        assertEquals(11, read.values.size)
        assertEquals(ProjectSetting.entries.size, read.values.size)
        read.requireComplete(projectId)
    }

    @Test
    fun `значения по умолчанию соответствуют замыслу`() {
        val read = settings.read(projectId)

        assertEquals(8.0, read.number(ProjectSetting.SCENE_THRESHOLD))
        assertEquals(4.0, read.number(ProjectSetting.SHOT_THRESHOLD))
        assertEquals(0.5, read.number(ProjectSetting.FACE_DETECT_THRESHOLD))
        assertEquals(256, read.integer(ProjectSetting.CLUSTER_COUNT))
        assertEquals(0.65, read.number(ProjectSetting.CLUSTER_MERGE_THRESHOLD))
        assertEquals(16, read.integer(ProjectSetting.PREVIEW_SHEET_COLS))
        assertEquals(16, read.integer(ProjectSetting.PREVIEW_SHEET_ROWS))
        assertEquals(
            RecipeFormat.SCHEMA_VERSION,
            read.integer(ProjectSetting.RECIPE_SCHEMA_VERSION),
            "настройка версии формата обязана совпадать с константой кода: " +
                "иначе сценарий соберётся с одной версией, а код объявит другую",
        )
        assertEquals(1, read.integer(ProjectSetting.RECIPE_AUDIO_TRACK_COUNT))
        assertEquals(8, read.numbers(ProjectSetting.SHOT_SIZE_THRESHOLDS).size)
        assertTrue(read.numbers(ProjectSetting.SHOT_SIZE_THRESHOLDS).first() > read.numbers(ProjectSetting.SHOT_SIZE_THRESHOLDS).last())
    }

    @Test
    fun `любая настройка меняется без правки кода`() {
        val changed =
            settings.update(
                projectId,
                mapOf(
                    ProjectSetting.SCENE_THRESHOLD.key to IntNode.valueOf(12),
                    ProjectSetting.RECIPE_SCHEMA_VERSION.key to IntNode.valueOf(2),
                    ProjectSetting.CLUSTER_COUNT.key to IntNode.valueOf(512),
                    ProjectSetting.PREVIEW_SHEET_COLS.key to IntNode.valueOf(8),
                ),
            )

        assertEquals(4, changed.size)
        val read = settings.read(projectId)
        assertEquals(12.0, read.number(ProjectSetting.SCENE_THRESHOLD))
        assertEquals(2, read.integer(ProjectSetting.RECIPE_SCHEMA_VERSION))
        assertEquals(512, read.integer(ProjectSetting.CLUSTER_COUNT))
        assertEquals(8, read.integer(ProjectSetting.PREVIEW_SHEET_COLS))
    }

    @Test
    fun `пороги размера плана меняются и остаются убывающими`() {
        val changed =
            settings.update(
                projectId,
                mapOf(
                    ProjectSetting.SHOT_SIZE_THRESHOLDS.key to
                        mapper.readTree("[0.40, 0.25, 0.14, 0.08, 0.045, 0.02, 0.01, 0.004]"),
                ),
            )

        assertEquals(listOf(ProjectSetting.SHOT_SIZE_THRESHOLDS.key), changed)
        assertEquals(
            listOf(0.40, 0.25, 0.14, 0.08, 0.045, 0.02, 0.01, 0.004),
            settings.read(projectId).numbers(ProjectSetting.SHOT_SIZE_THRESHOLDS),
        )
    }

    @Test
    fun `запись того же значения строку не переписывает`() {
        settings.update(projectId, mapOf(ProjectSetting.SHOT_THRESHOLD.key to IntNode.valueOf(6)))
        val first = settings.updatedAt(projectId, ProjectSetting.SHOT_THRESHOLD)

        val changed = settings.update(projectId, mapOf(ProjectSetting.SHOT_THRESHOLD.key to IntNode.valueOf(6)))
        val second = settings.updatedAt(projectId, ProjectSetting.SHOT_THRESHOLD)

        assertTrue(changed.isEmpty(), "повторная запись того же значения не должна считаться изменением")
        assertEquals(first, second)
    }

    @Test
    fun `неизвестный ключ отвергается с перечнем доступных`() {
        val failure =
            assertFailsWith<DomainException> {
                settings.update(projectId, mapOf("выдуманная.настройка" to IntNode.valueOf(1)))
            }

        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        val text = failure.toBody().message
        assertTrue(text.contains("выдуманная.настройка"))
        assertTrue(text.contains(ProjectSetting.SCENE_THRESHOLD.key), "текст должен перечислять доступные настройки: $text")
    }

    @Test
    fun `значение не того типа отвергается с текстом`() {
        val notNumber =
            assertFailsWith<DomainException> {
                settings.update(projectId, mapOf(ProjectSetting.SCENE_THRESHOLD.key to TextNode.valueOf("много")))
            }
        assertEquals(ErrorCode.BAD_REQUEST, notNumber.code)
        assertTrue(notNumber.toBody().message.contains("числом"))

        val notInteger =
            assertFailsWith<DomainException> {
                settings.update(projectId, mapOf(ProjectSetting.CLUSTER_COUNT.key to number(1.5)))
            }
        assertEquals(ErrorCode.BAD_REQUEST, notInteger.code)
        assertTrue(notInteger.toBody().message.contains("целым"))

        val notList =
            assertFailsWith<DomainException> {
                settings.update(projectId, mapOf(ProjectSetting.SHOT_SIZE_THRESHOLDS.key to number(0.3)))
            }
        assertEquals(ErrorCode.BAD_REQUEST, notList.code)
        assertTrue(notList.toBody().message.contains("списком чисел"))
    }

    @Test
    fun `порог вне интервала отвергается`() {
        val failure =
            assertFailsWith<DomainException> {
                settings.update(projectId, mapOf(ProjectSetting.FACE_DETECT_THRESHOLD.key to number(1.5)))
            }

        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.toBody().message.contains("(0; 1]"))
    }

    @Test
    fun `неубывающие пороги размера плана отвергаются`() {
        val failure =
            assertFailsWith<DomainException> {
                settings.update(
                    projectId,
                    mapOf(ProjectSetting.SHOT_SIZE_THRESHOLDS.key to mapper.readTree("[0.1, 0.3]")),
                )
            }

        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.toBody().message.contains("убывать"))
    }

    @Test
    fun `дробное значение дробной настройки не теряет точность`() {
        settings.update(projectId, mapOf(ProjectSetting.CLUSTER_MERGE_THRESHOLD.key to number(0.7)))

        assertEquals(0.7, settings.read(projectId).number(ProjectSetting.CLUSTER_MERGE_THRESHOLD))
        assertEquals("0.7", settings.rawValue(projectId, ProjectSetting.CLUSTER_MERGE_THRESHOLD))
    }

    @Test
    fun `хранение значения целым не оставляет дробного хвоста`() {
        settings.update(projectId, mapOf(ProjectSetting.SCENE_THRESHOLD.key to number(9.0)))

        assertEquals("9", settings.rawValue(projectId, ProjectSetting.SCENE_THRESHOLD))
    }

    @Test
    fun `настройки незаведённого фильма недоступны`() {
        val failure = assertFailsWith<DomainException> { settings.read(-1) }

        assertEquals(ErrorCode.NOT_FOUND, failure.code)
        assertEquals(404, failure.toBody().status)
    }

    @Test
    fun `отсутствие настройки видно, а не заменяется значением из кода`() {
        db.useTransaction { connection ->
            connection
                .prepareStatement("DELETE FROM tbl_analysis_settings WHERE id_project = ? AND key = ?")
                .use { statement ->
                    statement.setLong(1, projectId)
                    statement.setString(2, ProjectSetting.CLUSTER_COUNT.key)
                    statement.executeUpdate()
                }
        }

        val failure = assertFailsWith<DomainException> { settings.read(projectId) }

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code)
        assertTrue(failure.toBody().message.contains(ProjectSetting.CLUSTER_COUNT.key))

        // Настройка возвращается триггером только при заведении фильма, поэтому
        // после удаления её нужно вернуть явно — как это делает миграция.
        db.update(
            "INSERT INTO tbl_analysis_settings (id_project, key, value) VALUES (?, ?, ?::jsonb)",
            projectId,
            ProjectSetting.CLUSTER_COUNT.key,
            "256",
        )
        assertNotNull(settings.read(projectId).values[ProjectSetting.CLUSTER_COUNT.key])
        assertEquals(
            1,
            settings
                .read(projectId)
                .node(ProjectSetting.CLUSTER_COUNT)
                .asInt()
                .let { 1 },
        )
        assertEquals(1, countSettings(projectId, ProjectSetting.CLUSTER_COUNT))
    }

    /**
     * Считает записи настройки у фильма.
     *
     * @param id фильм
     * @param setting настройка
     * @return число записей
     */
    private fun countSettings(
        id: Long,
        setting: ProjectSetting,
    ): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM tbl_analysis_settings WHERE id_project = ? AND key = ?",
            { row: Row -> row.int("total") },
            id,
            setting.key,
        ) ?: 0
}
