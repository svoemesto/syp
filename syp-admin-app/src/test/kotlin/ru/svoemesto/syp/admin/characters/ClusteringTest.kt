package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Test
import ru.svoemesto.syp.admin.catalog.MovieSetting
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки кластеризации на холодном старте (задача T068).
 *
 * Кластеризация — **чистая функция** от векторов и двух чисел, поэтому
 * проверки идут без базы: подставлять настоящие настройки сериала здесь
 * незачем, а вот проверить, что разбиение не зависит от порядка данных,
 * обязательно.
 *
 * Требования задачи: избыточное разбиение векторов на центры со слиянием
 * близких по порогу косинусной близости; число центров и порог слияния —
 * настройки сериала; кластеры строятся **до** появления обученной модели.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ClusteringTest {
    private val clustering = Clustering()

    private val settings =
        ru.svoemesto.syp.admin.catalog.MovieSettings(
            mapOf(
                MovieSetting.CLUSTER_COUNT.key to
                    com.fasterxml.jackson.databind.node.IntNode
                        .valueOf(64),
                MovieSetting.CLUSTER_MERGE_THRESHOLD.key to
                    com.fasterxml.jackson.databind.node.DoubleNode
                        .valueOf(0.9),
            ),
        )

    /**
     * Вектор на заданном угле к оси X.
     *
     * @param degrees угол в градусах
     * @return вектор единичной длины
     */
    private fun direction(degrees: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        return floatArrayOf(cos(radians).toFloat(), kotlin.math.sin(radians).toFloat())
    }

    /**
     * Лицо с вектором на заданном угле.
     *
     * @param faceId идентификатор лица
     * @param degrees угол в градусах
     * @return точка кластеризации
     */
    private fun face(
        faceId: Long,
        degrees: Double,
    ): ClusterPoint = ClusterPoint(faceId, direction(degrees))

    @Test
    fun `один человек попадает в один кластер`() {
        val points =
            listOf(
                face(1, 0.0),
                face(2, 2.0),
                face(3, -3.0),
                face(4, 90.0),
            )

        val clusters = clustering.cluster(points, centerCount = 64, mergeThreshold = 0.9)

        assertEquals(
            2,
            clusters.size,
            "четыре лица, два направления — должно быть два кластера, а получилось " +
                "${clusters.map { it.faceIds }}",
        )
        val groups = clusters.map { it.faceIds.sorted() }.sortedBy { it.first() }
        assertEquals(listOf(listOf(1L, 2L, 3L), listOf(4L)), groups, "лица одного человека в одном кластере")
    }

    @Test
    fun `разные люди не сливаются ниже порога`() {
        val points = listOf(face(1, 0.0), face(2, 60.0), face(3, 120.0))

        val clusters = clustering.cluster(points, centerCount = 64, mergeThreshold = 0.99)

        assertEquals(
            3,
            clusters.size,
            "при пороге 0,99 лица под углами 60 градусов не должны сливаться, получили ${clusters.map { it.faceIds }}",
        )
    }

    @Test
    fun `разбиение не зависит от порядка данных`() {
        val points = listOf(face(5, 0.0), face(6, 1.0), face(7, 89.0), face(8, 91.0))

        val straight = clustering.cluster(points, centerCount = 64, mergeThreshold = 0.9)
        val shuffled = clustering.cluster(points.reversed(), centerCount = 64, mergeThreshold = 0.9)

        assertEquals(
            straight.map { it.faceIds.sorted() }.sortedBy { it.first() },
            shuffled.map { it.faceIds.sorted() }.sortedBy { it.first() },
            "один и тот же состав лиц обязан давать один и тот же результат",
        )
    }

    @Test
    fun `число центров и порог слияния берутся у сериала`() {
        val points = listOf(face(1, 0.0), face(2, 5.0), face(3, 180.0))

        val fromSettings = clustering.cluster(points, settings)

        assertEquals(
            clustering
                .cluster(points, centerCount = 64, mergeThreshold = 0.9)
                .map { it.faceIds.sorted() }
                .sortedBy { it.first() },
            fromSettings.map { it.faceIds.sorted() }.sortedBy { it.first() },
            "разбиение по настройкам сериала совпадает с разбиением по этим же числам",
        )
    }

    @Test
    fun `ключ кластера выводится из состава а не из порядка`() {
        val points = listOf(face(31, 0.0), face(17, 1.0), face(99, 180.0))

        val clusters = clustering.cluster(points, centerCount = 64, mergeThreshold = 0.9)

        val first = clusters.first { 31L in it.faceIds }
        assertEquals("cl-17", first.id, "ключ кластера — минимальный идентификатор лица в нём")
        assertEquals(2, first.size)
    }

    @Test
    fun `вектора разной длины смешивать нельзя`() {
        val points = listOf(ClusterPoint(1L, floatArrayOf(1f, 0f)), ClusterPoint(2L, floatArrayOf(1f, 0f, 0f)))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                clustering.cluster(points, centerCount = 4, mergeThreshold = 0.9)
            }
        assertTrue(
            failure.message?.contains("Вектора разной длины") == true,
            "смешение векторов разных моделей обязано быть отвергнуто, отвечает: ${failure.message}",
        )
    }

    @Test
    fun `косинусная близость нулевого вектора равна нулю`() {
        val zero = DoubleArray(3)
        val unit = doubleArrayOf(1.0, 0.0, 0.0)

        assertEquals(0.0, clustering.cosineSimilarity(zero, unit), 1e-12, "у нулевого вектора нет направления")
        assertEquals(1.0, clustering.cosineSimilarity(unit, unit), 1e-12, "вектор с самим собой единичен")
    }

    @Test
    fun `пустой список лиц не даёт кластеров`() {
        assertEquals(0, clustering.cluster(emptyList(), centerCount = 16, mergeThreshold = 0.9).size)
    }
}
