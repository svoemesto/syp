package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.MovieSetting
import ru.svoemesto.syp.admin.catalog.MovieSettings
import kotlin.math.sqrt

/**
 * Кластеризация лиц на холодном старте.
 *
 * До первого обучения модели нет, а имена персон уже нужны оператору.
 * Поэтому лица группируются в кластеры по похожете векторов признаков:
 * один человек на серии — один кластер, и оператор даёт кластеру имя.
 * Обучение после этого просто учит модель на уже названных классах
 * (FR-031, research.md Т-16).
 *
 * Алгоритм — **избыточное разбиение с последующим слиянием**:
 *
 * 1. вектора делятся на центры **больше, чем нужно**, по числу настройки
 *    `cluster.count`; лишние центры разбирают близкие друг другу лица на
 *    части и дают устойчивое начало, не зависящее от порядка данных;
 * 2. центры, чья **косинусная близость** выше порога `cluster.merge_threshold`,
 *    сливаются в один; слияние повторяется, пока центры не перестанут
 *    объединяться.
 *
 * Почему именно так, а не один проход «каждый к ближайшему центру»:
 * результат второго прохода зависел бы от того, в каком порядке вектора
 * пришли из базы, и одна и та же серия давала бы разные кластеры в разные
 * дни. Избыточное разбиение с последующим слиянием даёт один и тот же
 * результат при том же составе лиц.
 *
 * Мера похожести — **косинусная**, а не евклидова: вектора признаков лиц
 * отличаются по общей «яркости» ракурса и освещения, и евклидово расстояние
 * приняло бы поворот человека за другого человека. Косинус от этого
 * свободен: он смотрит только на направление.
 *
 * Настройки передаются вызывающим, а не читаются внутри: они принадлежат
 * сериалу, а у серии своего сериала нет. Чтение настроек внутри кластеризации
 * означало бы, что бин знает, чью серию он сейчас разбирает, — а этого он не
 * знает и знать не должен.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Clustering {
    /**
     * Строит кластеры по векторам лиц.
     *
     * @param points вектора лиц с идентификаторами
     * @param settings настройки сериала: из них берутся число центров и порог
     *   слияния
     * @return кластеры; кластер без идентификатора лица пуст не бывает
     * @throws ru.svoemesto.syp.core.contract.DomainException если у сериала нет
     *   настроек: значения по умолчанию создаёт триггер базы, и их отсутствие
     *   — дефект данных, а не «взять порог по умолчанию»
     */
    fun cluster(
        points: List<ClusterPoint>,
        settings: MovieSettings,
    ): List<FaceCluster> =
        cluster(
            points,
            settings.integer(MovieSetting.CLUSTER_COUNT),
            settings.number(MovieSetting.CLUSTER_MERGE_THRESHOLD),
        )

    /**
     * Строит кластеры по векторам с заданными параметрами.
     *
     * Параметры передаются отдельно от настроек сериала, чтобы проверка
     * считала разбиение, не заводя сериал и базу: кластеризация — чистая
     * функция от данных и двух чисел.
     *
     * @param points вектора лиц
     * @param centerCount сколько центров создать при избыточном разбиении
     * @param mergeThreshold порог косинусной близости для слияния, от 0 до 1
     * @return кластеры по убыванию числа лиц
     * @throws IllegalArgumentException если параметры вне разумных границ
     */
    fun cluster(
        points: List<ClusterPoint>,
        centerCount: Int,
        mergeThreshold: Double,
    ): List<FaceCluster> {
        require(centerCount > 0) { "Число центров должно быть положительным, задано $centerCount" }
        require(mergeThreshold in -1.0..1.0) {
            "Порог косинусной близости вне диапазона -1…1: $mergeThreshold"
        }
        require(points.all { it.vector.isNotEmpty() }) {
            "Пустой вектор в кластеризацию не идёт: у него нет направления"
        }
        if (points.isEmpty()) return emptyList()
        val dimension = points.first().vector.size
        require(points.all { it.vector.size == dimension }) {
            "Вектора разной длины (${points.map { it.vector.size }.distinct()}): " +
                "это векторы разных моделей, смешивать их нельзя (Р-09)"
        }

        // 1. Избыточное разбиение. Центров столько, сколько задано настройкой,
        //    но не больше, чем точек: иначе лишние центры пришлось бы наполнять
        //    повторными копиями одних и тех же точек, и после слияния один
        //    человек попал бы в кластер сам с собой во сто экземпляров.
        var centers = split(points, centerCount)

        // 2. Слияние близких центров до устойчивого состояния.
        var merged = true
        var rounds = 0
        while (merged && rounds < MERGE_ROUND_LIMIT) {
            merged = false
            rounds++
            val kept = mutableListOf<Center>()
            val consumed = BooleanArray(centers.size)
            for (i in centers.indices) {
                if (consumed[i]) continue
                val group = mutableListOf(centers[i])
                for (j in i + 1 until centers.size) {
                    if (consumed[j]) continue
                    val target = meanOf(group.map { it.center })
                    if (cosineSimilarity(target, centers[j].center) >= mergeThreshold) {
                        group.add(centers[j])
                        consumed[j] = true
                        merged = true
                    }
                }
                kept.add(Center(meanOf(group.map { it.center }), group.flatMap { it.members }))
            }
            centers = kept
        }

        return centers
            .filter { it.members.isNotEmpty() }
            .map { center ->
                FaceCluster(
                    id = clusterIdOf(center.members),
                    faceIds = center.members,
                    size = center.members.size,
                )
            }.sortedWith(compareByDescending<FaceCluster> { it.size }.thenBy { it.id })
    }

    /**
     * Распределяет точки по центрам равномерным шагом.
     *
     * Распределение идёт по порядку идентификаторов лиц, а не по порядку
     * чтения из базы: иначе один и тот же состав лиц давал бы разные
     * центры в зависимости от того, откуда их читали.
     *
     * @param points точки
     * @param centers сколько центров запрошено
     * @return центры с их точками; точек не больше, чем центров
     */
    private fun split(
        points: List<ClusterPoint>,
        centers: Int,
    ): List<Center> {
        val ordered = points.sortedBy { it.faceId }.take(minOf(centers, points.size))
        return ordered.map { point ->
            Center(
                center = DoubleArray(point.vector.size) { point.vector[it].toDouble() },
                members = listOf(point.faceId),
            )
        }
    }

    /**
     * Среднее векторов покомпонентно.
     *
     * @param vectors вектора
     * @return средний вектор
     */
    private fun meanOf(vectors: List<DoubleArray>): DoubleArray {
        val result = DoubleArray(vectors.first().size)
        vectors.forEach { vector ->
            vector.forEachIndexed { index, value -> result[index] += value }
        }
        result.indices.forEach { index -> result[index] /= vectors.size }
        return result
    }

    /**
     * Косинусная близость двух векторов.
     *
     * Нулевой вектор не имеет направления: его близость определить нельзя, и
     * метод возвращает ноль, а не единицу и не исключение. Ноль означает
     * «ничего общего» — консервативное значение, при котором лишнее слияние
     * невозможно.
     *
     * @param left первый вектор
     * @param right второй вектор
     * @return близость от -1 до 1
     */
    fun cosineSimilarity(
        left: DoubleArray,
        right: DoubleArray,
    ): Double {
        require(left.size == right.size) {
            "Вектора разной длины (${left.size} и ${right.size}): близость не определена"
        }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    /**
     * Ключ кластера.
     *
     * Ключ выводится из состава кластера, а не из порядка его появления:
     * номер центра после слияния зависит от того, в каком порядке шли
     * вектора, и менялся бы при каждом пересчёте. Минимальный идентификатор
     * лица в кластере устойчив — он меняется только тогда, когда меняется
     * само лицо, а не счётчик.
     *
     * @param faceIds идентификаторы лиц кластера
     * @return ключ кластера
     */
    private fun clusterIdOf(faceIds: List<Long>): String = "cl-" + (faceIds.minOrNull() ?: 0L)

    /**
     * Центр с принадлежащими ему лицами.
     *
     * @property center усреднённый вектор центра
     * @property members идентификаторы лиц
     */
    private class Center(
        val center: DoubleArray,
        val members: List<Long>,
    )

    companion object {
        /**
         * Предел кругов слияния.
         *
         * Страховка от зацикливания: каждый круг сливает хотя бы одну пару
         * центров, поэтому кругов не больше, чем центров. Предел нужен, чтобы
         * ошибка в пороге не превращала пересчёт в бесконечную работу на
         * первом же листе превью.
         */
        const val MERGE_ROUND_LIMIT: Int = 64
    }
}

/**
 * Точка кластеризации: лицо и его вектор.
 *
 * @property faceId идентификатор лица
 * @property vector вектор признаков
 */
data class ClusterPoint(
    val faceId: Long,
    val vector: FloatArray,
) {
    /** Равенство по значениям: массивы в Kotlin сравниваются по ссылке. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is ClusterPoint && faceId == other.faceId && vector.contentEquals(other.vector))

    /** Хеш по значениям, согласованный с [equals]. */
    override fun hashCode(): Int = faceId.hashCode() * 31 + vector.contentHashCode()

    /** Текстовое представление без содержимого вектора. */
    override fun toString(): String = "ClusterPoint(faceId=$faceId, размер=${vector.size})"
}

/**
 * Кластер похожих лиц.
 *
 * Кластеры **не хранятся**: они вычисляются из векторов при чтении. Хранение
 * означало бы второе место, где живёт истина о похожестве лиц, и его
 * пришлось бы пересчитывать вслед за каждым изменением эмбеддингов — с
 * окном, в котором сохранённый кластер не совпадает с векторами.
 *
 * Лица кластера, пока у него нет имени, принадлежат служебной персоне
 * «распознано, имя не подтверждено»: «нет персоны» выражается заглушкой,
 * а не пустой ссылкой (Р-12, `docs/domains/characters/components/persons.md`).
 *
 * @property id ключ кластера; он же идентификатор в `POST /api/clusters/{id}/person`
 * @property faceIds идентификаторы лиц кластера
 * @property size сколько лиц в кластере
 */
data class FaceCluster(
    val id: String,
    val faceIds: List<Long>,
    val size: Int,
)
