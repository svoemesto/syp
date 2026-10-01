package ru.svoemesto.syp.admin.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки карты ключевых кадров.
 *
 * Закрываются требования задачи T032: бит на кадр, длина `ceil(кадров / 8)`,
 * бит 1 — кадр ключевой, число установленных битов, запрет отдельной строки
 * на каждый кадр (Р-07) и направления округления границ фрагмента
 * (ADR-0006, FR-082).
 *
 * Тест не требует базы и не требует видеофайла: карта — чистая структура
 * данных. Сверка самой карты `GOT.S01E01` с эталоном старого проекта —
 * отдельная задача T040, ей нужен доступ к выгрузке `legacy-iGOT`.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class KeyframeMapTest {
    /**
     * Число кадров серии `GOT.S01E01`.
     *
     * @return 88 643 кадра
     */
    private fun gotS01E01Frames(): Int = 88_643

    @Test
    fun `длина карты равна округлению числа кадров вверх до байта`() {
        // 88 643 / 8 = 11 080,375 — округляем вверх до 11 081 байт.
        assertEquals(11_081, KeyframeMap.requiredLength(gotS01E01Frames()))
        assertEquals(1, KeyframeMap.requiredLength(1))
        assertEquals(1, KeyframeMap.requiredLength(8))
        assertEquals(2, KeyframeMap.requiredLength(9))
        assertEquals(11_081, KeyframeMap.empty(gotS01E01Frames()).byteLength)
    }

    @Test
    fun `первый кадр занимает старший бит первого байта`() {
        val map = KeyframeMap.build(frameCount = 16, keyframes = listOf(0, 7, 8, 15))

        assertTrue(map.isKeyframe(0))
        assertFalse(map.isKeyframe(1))
        assertTrue(map.isKeyframe(7))
        assertTrue(map.isKeyframe(8))
        assertFalse(map.isKeyframe(14))
        assertTrue(map.isKeyframe(15))

        val bytes = map.toByteArray()
        assertEquals(2, bytes.size)
        assertEquals(0b1000_0001.toByte(), bytes[0])
        assertEquals(0b1000_0001.toByte(), bytes[1])
    }

    @Test
    fun `считается число установленных битов`() {
        val map = KeyframeMap.build(frameCount = gotS01E01Frames(), keyframes = (0 until 792).map { it * 111 })

        assertEquals(792, map.keyframeCount())
    }

    @Test
    fun `номера кадров идут по возрастанию и совпадают с заданными`() {
        val wanted = listOf(0, 240, 479, 88_642)
        val map = KeyframeMap.build(frameCount = gotS01E01Frames(), keyframes = wanted)

        assertContentEquals(wanted, map.keyframeIndices())
    }

    @Test
    fun `признаки по кадрам собираются в карту без округления времени`() {
        val flags = MutableList(9) { false }
        flags[0] = true
        flags[8] = true

        val map = KeyframeMap.ofFlags(frameCount = 9, flags = flags)

        assertTrue(map.isKeyframe(0))
        assertFalse(map.isKeyframe(7))
        assertTrue(map.isKeyframe(8))
        assertEquals(2, map.keyframeCount())
    }

    @Test
    fun `признаков больше или меньше кадров — отказ`() {
        assertFailsWith<IllegalArgumentException> {
            KeyframeMap.ofFlags(frameCount = 4, flags = listOf(true, true, true, true, true))
        }
        assertFailsWith<IllegalArgumentException> {
            KeyframeMap.ofFlags(frameCount = 4, flags = listOf(true, true))
        }
    }

    @Test
    fun `разбор карты из базы проверяет длину`() {
        val original = KeyframeMap.build(frameCount = 9, keyframes = listOf(0, 8))

        val parsed = KeyframeMap.parse(frameCount = 9, bytes = original.toByteArray())

        assertEquals(original, parsed)
        assertFailsWith<IllegalArgumentException> {
            KeyframeMap.parse(frameCount = 9, bytes = ByteArray(1))
        }
    }

    @Test
    fun `карта неизменяема после построения`() {
        val map = KeyframeMap.build(frameCount = 16, keyframes = listOf(0))
        val copy = map.toByteArray()

        copy[0] = 0

        assertTrue(map.isKeyframe(0))
    }

    @Test
    fun `начало фрагмента округляется к ключевому кадру не позже расчётного`() {
        val map = KeyframeMap.build(frameCount = 1000, keyframes = listOf(0, 240, 500))

        assertEquals(240, map.lastKeyframeAtOrBefore(241))
        assertEquals(240, map.lastKeyframeAtOrBefore(240))
        assertEquals(500, map.lastKeyframeAtOrBefore(900))
        assertEquals(0, map.lastKeyframeAtOrBefore(0))
    }

    @Test
    fun `конец фрагмента округляется к ключевому кадру не раньше расчётного`() {
        val map = KeyframeMap.build(frameCount = 1000, keyframes = listOf(0, 240, 500))

        assertEquals(500, map.firstKeyframeAtOrAfter(241))
        assertEquals(500, map.firstKeyframeAtOrAfter(500))
        assertEquals(240, map.firstKeyframeAtOrAfter(100))
    }

    @Test
    fun `отсутствие соседнего ключевого кадра даёт пусто, а не выдуманный номер`() {
        val map = KeyframeMap.build(frameCount = 1000, keyframes = listOf(0, 500))

        assertNull(map.firstKeyframeAtOrAfter(501))
    }

    @Test
    fun `кадр вне серии отвергается`() {
        val map = KeyframeMap.empty(frameCount = 100)

        assertFailsWith<IllegalArgumentException> { map.isKeyframe(100) }
        assertFailsWith<IllegalArgumentException> { map.isKeyframe(-1) }
    }
}
