package ru.svoemesto.syp.core.recipe

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.contract.ErrorItem

/**
 * Источник сведений о ключевых кадрах серии.
 *
 * Интерфейс, а не конкретная карта: код сценария лежит в общем модуле
 * `syp-core`, а карта ключевых кадров хранится у серии и обслуживает ещё и
 * интерфейс админки. Смешивать их нельзя — общий модуль не знает, где его
 * вызвали (ADR-0011, последствие 3).
 *
 * @property frameCount число кадров серии
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
interface KeyframeLookup {
    /** Число кадров серии, которому соответствует карта. */
    val frameCount: Int

    /**
     * Ближайший ключевой кадр **не позже** указанного.
     *
     * @param frame номер кадра
     * @return номер ключевого кадра или `null`, если раньше него ключевых нет
     */
    fun lastKeyframeAtOrBefore(frame: Int): Int?

    /**
     * Ближайший ключевой кадр **не раньше** указанного.
     *
     * @param frame номер кадра
     * @return номер ключевого кадра или `null`, если после него ключевых нет
     */
    fun firstKeyframeAtOrAfter(frame: Int): Int?
}

/**
 * Фактические границы фрагмента после округления до ключевых кадров.
 *
 * @property cutFirstFrame начало фрагмента: не позже расчётного
 * @property cutLastFrame конец фрагмента: не раньше расчётного
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CutBoundaries(
    val cutFirstFrame: Int,
    val cutLastFrame: Int,
) {
    /** Число кадров фрагмента по фактическим границам. */
    val frameCount: Int
        get() = cutLastFrame - cutFirstFrame
}

/**
 * Один фрагмент с точки зрения расчётных величин: сколько кадров он даёт и с
 * какой частотой.
 *
 * @property frames число кадров по фактическим границам
 * @property timeBaseNum числитель частокадровой базы серии
 * @property timeBaseDen знаменатель частокадровой базы серии
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeSlice(
    val frames: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
)

/**
 * Расчёт фактических границ фрагмента.
 *
 * Сборка идёт потоковым копированием, а потоковое копирование режет по
 * ключевым кадрам: начать фрагмент с произвольного кадра нельзя. Отсюда две
 * пары границ — расчётная из разметки и фактическая после округления
 * (ADR-0006).
 *
 * **Направление округления фиксировано и обратным быть не может:** начало —
 * ближайший ключевой кадр не позже начала плана, конец — не раньше конца.
 * Обратное округление отрезало бы кадры плана, и сценарий перестал бы быть
 * подборкой (FR-082, research.md Т-28).
 *
 * Воркер на машине пользователя не обходит файл в поисках ключевых кадров: он
 * берёт готовые границы из сценария.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object RecipeFragmentPlan {
    /**
     * Считает фактические границы фрагмента.
     *
     * Если ключевого кадра в нужную сторону нет, берётся сама расчётная
     * граница: сузить фрагмент нельзя, а округлять нечем.
     *
     * @param firstFrame расчётная граница начала
     * @param lastFrame расчётная граница конца
     * @param keyframes ключевые кадры серии
     * @return фактические границы фрагмента
     * @throws DomainException с кодом `SOURCE_UNREADABLE`, если карты ключевых
     *   кадров у серии нет: выдать сценарий с выдуманными границами нельзя
     */
    fun cutBoundaries(
        firstFrame: Int,
        lastFrame: Int,
        keyframes: KeyframeLookup?,
    ): CutBoundaries {
        require(firstFrame >= 0 && lastFrame >= firstFrame) {
            "Расчётные границы фрагмента заданы неверно: $firstFrame…$lastFrame"
        }
        if (keyframes == null) {
            throw DomainException(
                ErrorCode.SOURCE_UNREADABLE,
                "у серии не посчитана карта ключевых кадров, фактические границы фрагмента " +
                    "вычислить нечем; округление на стыке выдумывать нельзя (FR-082)",
                listOf(ErrorItem("tbl_episodes", "?", "карта ключевых кадров не посчитана")),
            )
        }
        val cutFirst = keyframes.lastKeyframeAtOrBefore(firstFrame) ?: firstFrame
        val cutLast = keyframes.firstKeyframeAtOrAfter(lastFrame) ?: lastFrame
        require(cutFirst <= firstFrame && cutLast >= lastFrame) {
            "Фактические границы обязаны охватывать расчётные: получено " +
                "$cutFirst…$cutLast при расчётных $firstFrame…$lastFrame (ADR-0006)"
        }
        return CutBoundaries(cutFirst, cutLast)
    }

    /**
     * Суммирует расчётные величины подборки по **фактическим** границам.
     *
     * Считается по фактическим, а не по расчётным: пользователь увидит ровно
     * ту длительность, которую получит после округления на стыках (FR-083).
     * Фактические величины после работы измеряет воркер — на сервере их нет
     * и подменять ими расчёт нельзя.
     *
     * Длительность считается **накопителем в наносекундах** и округляется до
     * миллисекунд один раз, в конце: округление длительности кадра до целых
     * миллисекунд до суммы на тысяче кадров набегает в секунды, и расчётная
     * величина разошлась бы с той, что покажет пользователю воркер.
     *
     * @param slices фрагменты с числом кадров по фактическим границам и
     *   частокадровой базой их серий
     * @return пара «расчётная длительность в миллисекундах, расчётное число кадров»
     * @throws IllegalArgumentException если частокадровая база неположительна
     */
    fun expectedTotals(slices: List<RecipeSlice>): Pair<Long, Long> {
        var frames = 0L
        var nanoseconds = 0L
        slices.forEach { slice ->
            require(slice.frames >= 0) {
                "Число кадров фрагмента не может быть отрицательным, задано ${slice.frames}"
            }
            require(slice.timeBaseNum > 0 && slice.timeBaseDen > 0) {
                "Частокадровая база серии должна быть положительной, задано " +
                    "${slice.timeBaseNum}/${slice.timeBaseDen}"
            }
            val frameNanoseconds = slice.timeBaseNum.toLong() * NANOSECONDS_PER_SECOND / slice.timeBaseDen
            nanoseconds += slice.frames.toLong() * frameNanoseconds
            frames += slice.frames.toLong()
        }
        val durationMs = (nanoseconds + NANOSECONDS_PER_MILLISECOND / 2) / NANOSECONDS_PER_MILLISECOND
        return durationMs to frames
    }

    private const val NANOSECONDS_PER_SECOND: Long = 1_000_000_000L
    private const val NANOSECONDS_PER_MILLISECOND: Long = 1_000_000L
}
