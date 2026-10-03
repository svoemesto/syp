package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.ProjectSettings
import ru.svoemesto.syp.core.media.RawFrame

/**
 * Приёмник рамок, записывающий их в базу.
 *
 * Класс делает три вещи, каждая из которых иначе выполнялась бы «по памяти»
 * вызывающего:
 *
 * 1. **назначает персону по пропорциям**, а не одной на весь кадр: в кадре
 *    одновременно бывают и лица, и ложные срабатывания (T067). Рамка, которая
 *    лицом не является, получает служебную персону «не лицо» и остаётся
 *    видимой — удаление спрятало бы расхождение детектора с картинкой;
 * 2. **перезаписывает только лица автоматики**: нарисованные мышью строки
 *    остаются нетронутыми, повторный анализ не отменяет ручную правку
 *    (FR-032, SC-006);
 * 3. **не пишет в кадры без лиц**: пустой список означает «в кадре нет лиц»,
 *    и запись пустого списка означала бы лишний поход в базу на каждом
 *    кадре эпизода — при 88 643 кадрах это десятки тысяч холостых транзакций.
 *
 * @property faces хранилище лиц
 * @property nonPersonFilter отбрасывание рамок, которые лицом не являются
 * @property maxAspect порог пропорции из настроек фильма
 * @property unrecognizedId служебная персона «распознано, имя не подтверждено»
 * @property nonPersonId служебная персона «не лицо»
 * @property videofileId эпизод, к которой относятся рамки
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StoringFaceSink(
    private val faces: FaceStore,
    private val nonPersonFilter: NonPersonFilter,
    private val maxAspect: Double,
    private val unrecognizedId: Long,
    private val nonPersonId: Long,
    private val videofileId: Long,
) : FaceSink {
    /**
     * Записывает найденные лица кадра.
     *
     * @param frameNumber номер кадра
     * @param width ширина кадра
     * @param height высота кадра
     * @param found найденные лица
     */
    override fun accept(
        frameNumber: Int,
        width: Int,
        height: Int,
        found: List<DetectedFace>,
        frame: RawFrame,
    ) {
        if (found.isEmpty()) return
        faces.replaceAutoFrame(
            videofileId = videofileId,
            frameNumber = frameNumber,
            found = found,
            personOf = { detected ->
                nonPersonFilter.personFor(detected, maxAspect, unrecognizedId, nonPersonId)
            },
            frameWidth = width,
            frameHeight = height,
        )
    }
}

/**
 * Сборка приёмника рамок для эпизода.
 *
 * Служебные персоны читаются **один раз на эпизод**, а не на каждый кадр:
 * на 88 643 кадрах это 88 643 похода в базу за двумя строками, которые не
 * менялись с момента заведения фильма.
 *
 * @property faces хранилище лиц
 * @property persons сервис персон
 * @property nonPersonFilter отбрасывание рамок, которые лицом не являются
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceSinkFactory(
    private val faces: FaceStore,
    private val persons: PersonService,
    private val nonPersonFilter: NonPersonFilter,
    private val db: ru.svoemesto.syp.core.db.Db? = null,
    private val embeddings: FaceEmbeddingStore? = null,
    private val embedder: FaceEmbedderProcess? = null,
    private val modelKey: String = "",
) {
    /**
     * Собирает приёмник рамок для эпизода.
     *
     * @param videofile эпизод; из неё берётся фильм-владелец
     * @param settings настройки фильма: из них берётся порог пропорции
     * @return приёмник, пишущий рамки в базу
     */
    fun forVideofile(
        videofile: ru.svoemesto.syp.admin.catalog.Videofile,
        settings: ProjectSettings,
    ): FaceSink {
        val videofileId = requireNotNull(videofile.id) { "У эпизода «${videofile.name}» нет идентификатора: рамкам некуда писаться" }
        val unrecognized =
            requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.UNRECOGNIZED).id)
        val nonPerson = requireNotNull(persons.servicePerson(videofile.projectId, PersonKind.NONPERSON).id)
        val storing =
            StoringFaceSink(
                faces = faces,
                nonPersonFilter = nonPersonFilter,
                maxAspect = nonPersonFilter.thresholdOf(settings),
                unrecognizedId = unrecognized,
                nonPersonId = nonPerson,
                videofileId = videofileId,
            )
        // Эмбеддер оборачивает приёмник записи, а не заменяет его: вектору нужен
        // номер лица в базе, а он появляется только после вставки.
        val store = embeddings
        val program = embedder
        val database = db
        if (store == null || program == null || database == null || modelKey.isEmpty()) {
            return storing
        }
        return EmbeddingFaceSink(
            inner = storing,
            embedder = program,
            embeddings = store,
            innerStore = faces,
            db = database,
            modelKey = modelKey,
            videofileId = videofileId,
        )
    }
}
