package ru.svoemesto.syp.admin.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.analysis.Staleness
import ru.svoemesto.syp.admin.catalog.CatalogController
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.SerialStore
import ru.svoemesto.syp.admin.catalog.SeriesRegistration
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.catalog.SourceProbe
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.ExternalProgram

/**
 * Сборка компонентов домена каталога.
 *
 * Всё, что зависит от окружения, — параметры подключения к базе и путь к
 * программе `ffprobe` — читается из переменных окружения, а не задаётся
 * константой (constitution VIII.5, ADR-0010 ограничение 2). Секретов в коде
 * нет: пароль приходит строкой подключения из окружения и нигде не хранится.
 *
 * Путь к программе **фиксирован настройкой развёртывания**: он не приходит ни
 * из тела запроса, ни из данных задания, ни от пользователя. Иначе путь из
 * интернета попал бы в командную строку внешней программы (ADR-0010).
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class CatalogConfiguration {
    /**
     * Собирает доступ к базе.
     *
     * @return готовый доступ сырым JDBC
     * @throws IllegalStateException если параметры подключения не заданы:
     *   молчаливый дефолт означал бы подключение не к той базе
     */
    @Bean
    fun database(): Db =
        Db(
            url = required(ENV_DB_URL),
            user = required(ENV_DB_USER),
            password = required(ENV_DB_PASSWORD),
        )

    /**
     * Собирает точку запуска внешних программ.
     *
     * @return исполнитель внешних программ с объединённым выводом и проверкой
     *   кода возврата
     */
    @Bean
    fun externalProgram(): ExternalProgram = ExternalProgram()

    /**
     * Собирает опрос файла серии.
     *
     * @param externalProgram исполнитель внешних программ
     * @return опрос файла серии
     */
    @Bean
    fun sourceProbe(externalProgram: ExternalProgram): SourceProbe =
        SourceProbe(externalProgram, optional(ENV_FFPROBE_PATH) ?: DEFAULT_FFPROBE_PATH)

    /**
     * Собирает хранилище сериалов.
     *
     * @param database доступ к базе
     * @return хранилище сериалов
     */
    @Bean
    fun serialStore(database: Db): SerialStore = SerialStore(database)

    /**
     * Собирает хранилище серий.
     *
     * @param database доступ к базе
     * @return хранилище серий
     */
    @Bean
    fun seriesStore(database: Db): SeriesStore = SeriesStore(database)

    /**
     * Собирает хранилище настроек сериала.
     *
     * @param database доступ к базе
     * @return хранилище настроек
     */
    @Bean
    fun serialSettingsStore(database: Db): SerialSettingsStore = SerialSettingsStore(database)

    /**
     * Собирает регистрацию серии.
     *
     * @param serialStore хранилище сериалов
     * @param seriesStore хранилище серий
     * @param sourceProbe опрос файла серии
     * @return регистрация серии
     */
    @Bean
    fun seriesRegistration(
        serialStore: SerialStore,
        seriesStore: SeriesStore,
        sourceProbe: SourceProbe,
    ): SeriesRegistration = SeriesRegistration(serialStore, seriesStore, sourceProbe)

    /**
     * Собирает эндпоинты приёма сериала и серии.
     *
     * @param serialStore хранилище сериалов
     * @param seriesStore хранилище серий
     * @param settingsStore хранилище настроек
     * @param seriesRegistration регистрация серии
     * @param staleness пометка результатов устаревшими при смене настройки
     * @return контроллер приёма
     */
    @Bean
    fun catalogController(
        serialStore: SerialStore,
        seriesStore: SeriesStore,
        settingsStore: SerialSettingsStore,
        seriesRegistration: SeriesRegistration,
        staleness: Staleness,
    ): CatalogController = CatalogController(serialStore, seriesStore, settingsStore, seriesRegistration, staleness = staleness)

    /**
     * Собирает разбор значений настроек.
     *
     * @return объект разбора JSON
     */
    @Bean
    fun catalogObjectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()

    companion object {
        /** Имя переменной окружения со строкой подключения к базе. */
        const val ENV_DB_URL: String = "SYP_DB_URL"

        /** Имя переменной окружения с пользователем базы. */
        const val ENV_DB_USER: String = "SYP_DB_USER"

        /** Имя переменной окружения с паролем базы. */
        const val ENV_DB_PASSWORD: String = "SYP_DB_PASSWORD"

        /** Имя переменной окружения с путём к программе `ffprobe`. */
        const val ENV_FFPROBE_PATH: String = "SYP_FFPROBE_PATH"

        /**
         * Путь к программе, если переменная окружения не задана.
         *
         * Значение фиксированное и одинаковое для всех развёртываний: образ
         * ставит программу в одно место, и путь из данных задания не приходит
         * (ADR-0010).
         */
        const val DEFAULT_FFPROBE_PATH: String = "/usr/bin/ffprobe"
    }
}

/**
 * Чтение переменной окружения со значением по умолчанию.
 *
 * Отдельная функция, а не чтение `System.getenv` в строке: значение по
 * умолчанию у пути к программе — это фиксированное место установки в образе,
 * а не секрет, и подставлять его молча можно только там, где это действительно
 * путь к программе (ADR-0010, ограничение 2).
 *
 * @param name имя переменной
 * @return значение или `null`, если переменная не задана либо пуста
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun optional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
