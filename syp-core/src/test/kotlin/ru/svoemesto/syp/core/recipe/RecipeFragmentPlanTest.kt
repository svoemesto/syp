package ru.svoemesto.syp.core.recipe

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.contract.DomainException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Тесты совместимости серий и расчёта фактических границ.
 *
 * Обе проверки обязаны выполняться **до** выдачи сценария: узнать о
 * несовместимости через полчаса работы на своей машине пользователь не должен
 * (FR-087), и округление границ обязано быть известно заранее (FR-082).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Совместимость серий и фактические границы")
class RecipeFragmentPlanTest {
    /** Карта ключевых кадров: ключевой каждый сотый кадр плюс первый. */
    private val keyframes: KeyframeLookup =
        object : KeyframeLookup {
            override val frameCount: Int = 2000

            override fun lastKeyframeAtOrBefore(frame: Int): Int? {
                if (frame < 0) return null
                return (frame / 100) * 100
            }

            override fun firstKeyframeAtOrAfter(frame: Int): Int? {
                val candidate = ((frame + 99) / 100) * 100
                return candidate.takeIf { it < frameCount }
            }
        }

    private fun episode(
        id: Long,
        name: String = "GOT.S01E0$id",
        videoProfile: String? = "High",
        pixelFormat: String = "yuv420p",
        width: Int = 1920,
        height: Int = 1080,
        timeBaseNum: Int = 1001,
        timeBaseDen: Int = 24000,
    ) = EpisodeParameters(
        episodeId = id,
        name = name,
        width = width,
        height = height,
        videoCodec = "h264",
        videoProfile = videoProfile,
        pixelFormat = pixelFormat,
        timeBaseNum = timeBaseNum,
        timeBaseDen = timeBaseDen,
        audioCodec = "aac",
        audioChannels = 2,
        audioSampleRate = 48000,
    )

    @Test
    @DisplayName("Серия одна — совместима сама с собой")
    fun singleEpisodeIsCompatible() {
        val report = RecipeCompatibility.check(listOf(episode(7)))
        assertTrue(report.isCompatible, "единственная серия не может быть несовместима сама с собой")
        assertEquals(7L, report.reference.episodeId)
    }

    @Test
    @DisplayName("Одинаковые параметры дают совместимость")
    fun equalParametersAreCompatible() {
        val report = RecipeCompatibility.check(listOf(episode(7), episode(8)))
        assertTrue(report.isCompatible, "серии с одинаковыми параметрами совместимы")
    }

    @Test
    @DisplayName("Расхождение профиля и формата пикселей перечисляется в ответе")
    fun differingAttributesAreReported() {
        val report =
            RecipeCompatibility.check(
                listOf(episode(7), episode(8, videoProfile = "Main", pixelFormat = "yuv422p")),
            )
        assertTrue(!report.isCompatible, "разные параметры склейки означают несовместимость")
        assertEquals(listOf("pixelFormat", "videoProfile"), report.differingAttributes)
        assertEquals(
            8L,
            report.incompatible
                .single()
                .parameters.episodeId,
        )
    }

    @Test
    @DisplayName("Разная частокадровая база означает несовместимость")
    fun frameRateBaseIsCompared() {
        val report = RecipeCompatibility.check(listOf(episode(7), episode(8, timeBaseNum = 1000)))
        assertEquals(listOf("frameRateBase"), report.differingAttributes)
    }

    @Test
    @DisplayName("Пустой список серий — отказ, а не «всё совместимо»")
    fun emptySelectionIsRejected() {
        assertFailsWith<IllegalArgumentException> { RecipeCompatibility.check(emptyList()) }
    }

    @Test
    @DisplayName("Начало округляется к ключевому кадру не позже, конец — не раньше")
    fun boundariesRoundInSafeDirection() {
        val boundaries = RecipeFragmentPlan.cutBoundaries(1120, 1455, keyframes)
        assertEquals(1100, boundaries.cutFirstFrame, "начало округляется вниз, кадры плана не теряются")
        assertEquals(1500, boundaries.cutLastFrame, "конец округляется вверх, кадры плана не теряются")
        assertTrue(
            boundaries.cutFirstFrame <= 1120 && boundaries.cutLastFrame >= 1455,
            "фактические границы обязаны охватывать расчётные (ADR-0006)",
        )
    }

    @Test
    @DisplayName("Без карты ключевых кадров сценарий не выдаётся")
    fun missingKeyframeMapIsRefused() {
        val failure =
            assertFailsWith<DomainException> {
                RecipeFragmentPlan.cutBoundaries(1120, 1455, null)
            }
        assertEquals(
            "у серии не посчитана карта ключевых кадров, фактические границы фрагмента вычислить нечем; " +
                "округление на стыке выдумывать нельзя (FR-082)",
            failure.detail,
        )
    }

    @Test
    @DisplayName("Расчётные величины считаются по фактическим границам")
    fun expectedTotalsUseActualBoundaries() {
        val (durationMs, frames) =
            RecipeFragmentPlan.expectedTotals(
                listOf(
                    RecipeSlice(273, 1001, 24000),
                    RecipeSlice(300, 1001, 24000),
                ),
            )
        assertEquals(573L, frames, "кадры суммируются по фактическим границам")
        // 573 кадра по 1001/24000 секунды: округление до миллисекунд делается
        // один раз, в конце, а не на каждом кадре.
        assertEquals(23899L, durationMs, "длительность считается по точному частокадровому отношению")
    }
}
