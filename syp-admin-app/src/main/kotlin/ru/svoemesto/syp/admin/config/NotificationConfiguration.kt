package ru.svoemesto.syp.admin.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.notify.JobQueueNotifier
import ru.svoemesto.syp.admin.notify.NotificationPublisher
import ru.svoemesto.syp.admin.notify.QueueStateReader
import ru.svoemesto.syp.admin.notify.SseNotificationService
import ru.svoemesto.syp.admin.notify.SubscribeController
import ru.svoemesto.syp.admin.notify.TabIdFilter
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.JobQueueListener

/**
 * Сборка подсистемы нотификаций SSE.
 *
 * Собирается отдельной конфигурацией, а не внутри существующих: уведомления —
 * сквозная подсистема, а не часть очереди заданий, выдачи сценария или
 * подсчёта суммы. Разделение видно и по списку зависимостей: сервис
 * нотификаций не знает ни о ком, кроме базы и разбора JSON, и добавление
 * второго потребителя не трогает ни одного из них.
 *
 * Между очередью заданий и подсистемой нотификаций стоит [JobQueueListener]:
 * очередь не знает, кто слушает, и не знает, что они вообще есть. Обратная
 * связь тоже запрещена — подписчик не может изменить состояние задания из
 * обработчика.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class NotificationConfiguration {
    /**
     * Собирает чтение состояния очереди.
     *
     * @param database доступ к базе
     * @return счётчик состояний очереди
     */
    @Bean
    fun queueStateReader(database: Db): QueueStateReader = QueueStateReader(database)

    /**
     * Собирает нотификации SSE.
     *
     * Сердцебие запускается при создании бина и останавливается при закрытии
     * контекста: расписание, оставшееся после остановки приложения, держало бы
     * поток и не давало бы контексту закрыться.
     *
     * @param mapper разбор значений в JSON
     * @return сервис нотификаций
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun sseNotificationService(mapper: ObjectMapper): SseNotificationService = SseNotificationService(mapper)

    /**
     * Собирает перевод изменений очереди в уведомления.
     *
     * @param notifications нотификации SSE
     * @param queueStateReader состояние очереди
     * @return слушатель очереди заданий
     */
    @Bean
    fun jobQueueNotifier(
        notifications: SseNotificationService,
        queueStateReader: QueueStateReader,
    ): JobQueueNotifier = JobQueueNotifier(notifications, queueStateReader)

    /**
     * Собирает публикацию уведомлений о событиях домена.
     *
     * @param notifications нотификации SSE
     * @return публикатор уведомлений домена
     */
    @Bean
    fun notificationPublisher(notifications: SseNotificationService): NotificationPublisher = NotificationPublisher(notifications)

    /**
     * Собирает эндпоинт подписки.
     *
     * @param notifications нотификации SSE
     * @param queueStateReader состояние очереди для первого события подписки
     * @return контроллер подписки
     */
    @Bean
    fun subscribeController(
        notifications: SseNotificationService,
        queueStateReader: QueueStateReader,
    ): SubscribeController = SubscribeController(notifications, queueStateReader)

    /**
     * Регистрирует фильтр идентификатора вкладки.
     *
     * Порядок важен: фильтр должен идти раньше обработчика запросов, иначе
     * контроллер подписки не увидит идентификатор вкладки.
     *
     * @return регистрация фильтра
     */
    @Bean
    fun tabIdFilterRegistration(): FilterRegistrationBean<TabIdFilter> =
        FilterRegistrationBean(TabIdFilter()).apply {
            order = FilterRegistrationBean.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }
}
