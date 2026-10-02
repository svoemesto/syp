package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.MovieSettings

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
 *    кадре серии — при 88 643 кадрах это десятки тысяч холостых транзакций.
 *
 * @property faces хранилище лиц
 * @property nonPersonFilter отбрасывание рамок, которые лицом не являются
 * @property maxAspect порог пропорции из настроек сериала
 * @property unrecognizedId служебная персона «распознано, имя не подтверждено»
 * @property nonPersonId служебная персона «не лицо»
 * @property episodeId серия, к которой относятся рамки
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StoringFaceSink(
    private val faces: FaceStore,
    private val nonPersonFilter: NonPersonFilter,
    private val maxAspect: Double,
    private val unrecognizedId: Long,
    private val nonPersonId: Long,
    private val episodeId: Long,
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
    ) {
        if (found.isEmpty()) return
        faces.replaceAutoFrame(
            episodeId = episodeId,
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
 * Сборка приёмника рамок для серии.
 *
 * Служебные персоны читаются **один раз на серию**, а не на каждый кадр:
 * на 88 643 кадрах это 88 643 похода в базу за двумя строками, которые не
 * менялись с момента заведения сериала.
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
) {
    /**
     * Собирает приёмник рамок для серии.
     *
     * @param episode серия; из неё берётся сериал-владелец
     * @param settings настройки сериала: из них берётся порог пропорции
     * @return приёмник, пишущий рамки в базу
     */
    fun forEpisode(
        episode: ru.svoemesto.syp.admin.catalog.Episode,
        settings: MovieSettings,
    ): FaceSink {
        val episodeId = requireNotNull(episode.id) { "У серии «${episode.name}» нет идентификатора: рамкам некуда писаться" }
        val unrecognized =
            requireNotNull(persons.servicePerson(episode.movieId, PersonKind.UNRECOGNIZED).id)
        val nonPerson = requireNotNull(persons.servicePerson(episode.movieId, PersonKind.NONPERSON).id)
        return StoringFaceSink(
            faces = faces,
            nonPersonFilter = nonPersonFilter,
            maxAspect = nonPersonFilter.thresholdOf(settings),
            unrecognizedId = unrecognized,
            nonPersonId = nonPerson,
            episodeId = episodeId,
        )
    }
}
