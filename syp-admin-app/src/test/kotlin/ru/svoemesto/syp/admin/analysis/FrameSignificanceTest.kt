package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.SerialStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.db.Db
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки таблицы значимых кадров.
 *
 * Закрывают требования задачи T050:
 *
 * 1. **строка создаётся только для значимого кадра** — с границей сцены,
 *    границей плана, найденным лицом или подсказкой смены крупности (FR-024);
 * 2. **полной таблицы на 88 643 строки нет**: кадр без признаков создать
 *    нельзя ни кодом, ни базой (Р-07);
 * 3. **признаки одного кадра не разъезжаются**: повторная запись того же
 *    кадра дополняет его признаки, а не плодит вторую строку.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrameSignificanceTest {
    private lateinit var db: Db
    private lateinit var seriesStore: SeriesStore
    private lateinit var store: FrameSignificanceStore

    /**
     * Поднимает доступ к базе и хранилище кадров.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        seriesStore = SeriesStore(db)
        store = FrameSignificanceStore(db)
    }

    /**
     * Заводит серию с указанным числом кадров.
     *
     * @param frameCount число кадров серии
     * @return записанная серия
     */
    private fun newSeries(frameCount: Int): Series {
        val serials = SerialStore(db)
        val serial = serials.create("Кадры ${System.nanoTime()}", "/srv/got")
        return seriesStore.insert(
            Series(
                serialId = serial.id!!,
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
    fun `кадр без признаков не создаётся`() {
        val series = newSeries(500)

        assertFailsWith<IllegalArgumentException> {
            FrameSignificance(seriesId = series.id!!, frameNumber = 10)
        }

        store.upsertAll(listOf(FrameSignificance(seriesId = series.id!!, frameNumber = 11, faceCount = 2)))

        assertEquals(1, store.countBySeries(series.id!!))
        assertNull(store.listRange(series.id!!, 10, 10).firstOrNull(), "для кадра без признаков строки нет")
    }

    @Test
    fun `признаки одного кадра не разъезжаются по строкам`() {
        val series = newSeries(500)
        store.upsertAll(listOf(FrameSignificance(seriesId = series.id!!, frameNumber = 20, faceCount = 1)))
        store.upsertAll(
            listOf(
                FrameSignificance(seriesId = series.id!!, frameNumber = 20, isShotBoundary = true),
                FrameSignificance(seriesId = series.id!!, frameNumber = 20, isSceneBoundary = true),
            ),
        )

        val stored = store.listRange(series.id!!, 20, 20)
        assertEquals(1, stored.size, "один кадр — одна строка: три источника признаков не плодят три строки")
        val frame = stored.single()
        assertTrue(frame.isSceneBoundary && frame.isShotBoundary && frame.faceCount == 1, "все признаки собраны в одной строке")
    }

    @Test
    fun `границы поднимаются у уже существующих кадров`() {
        val series = newSeries(500)
        store.upsertAll(
            listOf(
                FrameSignificance(seriesId = series.id!!, frameNumber = 30, faceCount = 3),
                FrameSignificance(seriesId = series.id!!, frameNumber = 31, faceCount = 1),
            ),
        )

        // Возвращается число затронутых строк: у кадра 31 признак
        // поднимается в существующей строке, кадр 200 заводится заново.
        assertEquals(1, store.markSceneBoundaries(series.id!!, listOf(30)))
        assertEquals(2, store.markShotBoundaries(series.id!!, listOf(31, 200)))

        val frames = store.listRange(series.id!!, 29, 32)
        assertEquals(2, frames.size, "новый кадр границы и кадр без границы — всего две строки")
        assertTrue(frames.first { it.frameNumber == 30 }.isSceneBoundary)
        assertTrue(frames.first { it.frameNumber == 30 }.faceCount == 3, "признак лица не потерялся при поднятии границы")
        assertTrue(frames.first { it.frameNumber == 31 }.isShotBoundary)
        assertEquals(
            3,
            store.countBySeries(series.id!!),
            "добавилась строка кадра 200: значимых кадров ${store.listRange(series.id!!, 0, 500).map { it.frameNumber }}",
        )
    }

    @Test
    fun `на серии 88 643 кадра значимых кадров немного`() {
        val series = newSeries(88_643)
        val boundaries = listOf(0, 150, 1500, 88_000)

        store.upsertAll(
            boundaries.map { frame -> FrameSignificance(seriesId = series.id!!, frameNumber = frame, isSceneBoundary = true) },
        )

        assertEquals(boundaries.size, store.countBySeries(series.id!!))
        assertTrue(
            store.countBySeries(series.id!!) < series.frameCount / 100,
            "значимых кадров на два порядка меньше, чем кадров серии: полной таблицы нет (Р-07)",
        )
    }
}
