package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.media.FrameCrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки рамки вырезки миниатюры лица.
 *
 * Рамка нужна для миниатюры: вырезку делает декодер, и она должна выходить за
 * пределы кадра ровно настолько, насколько позволяет сам кадр.
 */
class FaceCropTest {
    /**
     * Рамка лица в указанных границах.
     *
     * @param x1 левый край лица
     * @param y1 верхний край лица
     * @param x2 правый край лица
     * @param y2 нижний край лица
     * @return лицо с нужными границами
     */
    private fun face(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) = Face(
        videofileId = 1,
        frameNumber = 0,
        faceIndex = 0,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        personId = 1,
    )

    @Test
    fun `рамка расширяется вокруг лица`() {
        val crop = FaceCrop.of(face(x1 = 100, y1 = 100, x2 = 200, y2 = 200), frameWidth = 1920, frameHeight = 1080)

        assertTrue(crop.x < 100, "рамка шире лица слева: ${crop.x}")
        assertTrue(crop.y < 100, "рамка шире лица сверху: ${crop.y}")
        assertTrue(crop.x + crop.width > 200, "рамка шире лица справа: ${crop.x + crop.width}")
        assertTrue(crop.y + crop.height > 200, "рамка шире лица снизу: ${crop.y + crop.height}")
    }

    @Test
    fun `рамка у края кадра не выходит за него`() {
        val crop = FaceCrop.of(face(x1 = 0, y1 = 0, x2 = 40, y2 = 40), frameWidth = 1920, frameHeight = 1080)

        assertEquals(0, crop.x, "у левого края рамка не выходит за кадр")
        assertEquals(0, crop.y, "у верхнего края рамка не выходит за кадр")
        assertTrue(crop.x + crop.width <= 1920, "рамка не выходит за правый край")
        assertTrue(crop.y + crop.height <= 1080, "рамка не выходит за нижний край")
    }

    @Test
    fun `рамка у правого и нижнего края кадра не выходит за него`() {
        val crop = FaceCrop.of(face(x1 = 1880, y1 = 1040, x2 = 1920, y2 = 1080), frameWidth = 1920, frameHeight = 1080)

        assertTrue(crop.x >= 0, "рамка не выходит за левый край")
        assertTrue(crop.y >= 0, "рамка не выходит за верхний край")
        assertEquals(1920, crop.x + crop.width, "рамка обрезана по правому краю кадра")
        assertEquals(1080, crop.y + crop.height, "рамка обрезана по нижнему краю кадра")
    }

    @Test
    fun `вырожденная рамка отвергается`() {
        val failure =
            runCatching { FrameCrop(x = 0, y = 0, width = 0, height = 10) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "нулевая ширина отвергается: $failure")
    }
}
