package ru.svoemesto.syp.admin.annotation

import org.junit.jupiter.api.Test
import ru.svoemesto.syp.admin.analysis.ShotSize
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Проверки шкалы размера плана.
 *
 * Проверка без базы и без файлов намеренно: правило «доля кадра → ступень» это
 * чистая функция от настроек фильма (ADR-0003), и проверять его на живой базе
 * было бы проверкой не того — базы в нём нет вовсе.
 *
 * Пороги, которые используются в проверке, — те же значения, что задаёт
 * миграция настройкой по умолчанию. Подбор порогов замером М-04 не выполнен,
 * поэтому проверка фиксирует **правило сопоставления**, а не правильность самих
 * чисел: числа меняются, правило обязано работать при любых из них.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ShotSizeScaleTest {
    private val thresholds = listOf(0.35, 0.22, 0.13, 0.075, 0.04, 0.02, 0.009, 0.003)

    private val scale = ShotSizeScale(thresholds)

    @Test
    fun `ступень выбирается числом порогов, которых доля не достигает`() {
        assertEquals(ShotSize.ECU, scale.sizeOf(0.9), "выше всех порогов — самая крупная ступень")
        assertEquals(ShotSize.ECU, scale.sizeOf(0.35), "ровно первый порог остаётся в ECU")
        assertEquals(ShotSize.BCU, scale.sizeOf(0.34), "чуть ниже первого порога — следующая ступень")
        assertEquals(ShotSize.MS, scale.sizeOf(0.05), "между 0,075 и 0,04 — средний план")
        assertEquals(ShotSize.MLS, scale.sizeOf(0.03), "между 0,04 и 0,02 — средний общий план")
        assertEquals(ShotSize.XLS, scale.sizeOf(0.001), "ниже всех порогов — очень общий план")
    }

    @Test
    fun `каждая ступень достижима при своих границах`() {
        val reached =
            thresholds.indices.toSet().map { band ->
                val share = if (band == 0) 1.0 else (thresholds[band - 1] + thresholds[band]) / 2
                scale.sizeOf(share).name
            }
        assertEquals(
            listOf("ECU", "BCU", "CU", "MCU", "MS", "MLS", "LS", "VLS"),
            reached,
            "восемь порогов обязаны давать восемь разных ступеней: иначе часть шкалы " +
                "недостижима ни при каком лице",
        )
    }

    @Test
    fun `шкала без порогов и с неубывающими порогами отвергается`() {
        assertFailsWith<IllegalArgumentException> { ShotSizeScale(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ShotSizeScale(listOf(0.1, 0.2)) }
        assertFailsWith<IllegalArgumentException> { ShotSizeScale(listOf(1.5, 0.2)) }
    }
}
