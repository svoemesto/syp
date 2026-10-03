package ru.svoemesto.syp.admin.characters

import org.slf4j.LoggerFactory
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.RawFrame

/**
 * Приёмник, который считает вектор лица сразу после записи лица.
 *
 * Стоит после приёмника записи, а не вместо него: вектору нужен номер лица в
 * базе, а он появляется только после вставки. Внутри одного кадра порядок
 * жёсткий — записать, потом посчитать, — иначе вектор пришлось бы искать по
 * рамке, а рамки в кадре повторяются.
 *
 * @property inner приёмник, который пишет лица
 * @property embedder программа эмбеддера
 * @property embeddings хранилище векторов
 * @property innerStore хранилище лиц: из него читаются номера записанных лиц
 * @property db соединение с базой
 * @property modelKey ключ модели в базе: по нему вектор отбрасывается, когда
 *   модель меняется
 * @property videofileId видеофайл, которому принадлежат лица
 */
class EmbeddingFaceSink(
    private val inner: FaceSink,
    private val embedder: FaceEmbedderProcess,
    private val embeddings: FaceEmbeddingStore,
    private val innerStore: FaceStore,
    private val db: Db,
    private val modelKey: String,
    private val videofileId: Long,
) : FaceSink {
    /**
     * Записывает лица и считает по ним векторы.
     *
     * @param frameNumber номер кадра
     * @param width ширина кадра
     * @param height высота кадра
     * @param found найденные лица
     * @param frame кадр целиком
     */
    override fun accept(
        frameNumber: Int,
        width: Int,
        height: Int,
        found: List<DetectedFace>,
        frame: RawFrame,
    ) {
        inner.accept(frameNumber, width, height, found, frame)
        if (found.isEmpty()) {
            return
        }
        val vectors =
            try {
                embedder.embed(frame, found)
            } catch (failure: FaceEmbedderFailed) {
                // Эмбеддер не ответил — лица уже записаны, и терять их из-за
                // вектора нельзя: они нужны оператору и для показа. Падение здесь
                // унесло бы весь проход, поэтому вектора не будет, а работа
                // продолжится. Причина уходит в журнал и видна при разборе.
                logger.error(
                    "Вектора лиц кадра $frameNumber не посчитаны: ${failure.message}",
                    failure,
                )
                return
            }
        if (vectors.size != found.size) {
            logger.error(
                "Эмбеддер вернул {} векторов на {} лиц кадра $frameNumber — вектора не записаны",
                vectors.size,
                found.size,
            )
            return
        }
        val stored = innerStore.listOfFrame(videofileId, frameNumber)
        if (stored.isEmpty()) {
            // Раньше этот выход был молчаливым, и видит в журнале. Из-за этого
            // невозможно было сказать, где именно обрывается запись: ноль
            // векторов выглядел одинаково и при пустом кадре, и при сбое записи.
            logger.error(
                "Вектора кадра $frameNumber не записаны: записанных лиц нет " +
                    "(найдено ${found.size})",
            )
            return
        }
        val byIndex = stored.associateBy { it.faceIndex }
        val rows =
            found.mapIndexedNotNull { index, _ ->
                val face = byIndex[index] ?: return@mapIndexedNotNull null
                // Нулевой вектор — это не признак лица, а отказ программы посчитать:
                // она так отвечает на вырожденные точки, чтобы не рвать проход. Такое
                // в базу не пишется — иначе нули сравнимы с любым лицом и при
                // сравнении подменяют настоящие признаки.
                val vector = vectors[index]
                if (vector.all { it == 0f }) {
                    logger.error(
                        "Кадр $frameNumber, лицо ${face.id}: эмбеддер вернул нулевой вектор, " +
                            "лицо не сохранено",
                    )
                    return@mapIndexedNotNull null
                }
                FaceEmbedding(face.id!!, modelKey, vector)
            }
        if (rows.isEmpty()) {
            logger.error(
                "Вектора кадра $frameNumber не записаны: номера лиц не сошлись, " +
                    "записано ${stored.size}, найдено ${found.size}",
            )
            return
        }
        db.useTransaction { connection -> embeddings.saveAll(connection, rows) }
    }

    private companion object {
        /** Журнал приёмника. */
        val logger = LoggerFactory.getLogger("EmbeddingFaceSink")
    }
}
