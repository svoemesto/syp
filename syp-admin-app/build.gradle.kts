// SYP — бэкенд админки.
//
// Здесь есть исполнитель заданий: он берёт виды `ANALYZE`, `FACES`, `TRAIN`,
// `HASH` (research.md Т-20, FR-085). Вида `ASSEMBLE` не существует: видео на
// сервере не собирается (ADR-0009).
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":syp-core"))

    // Только web и JDBC-драйвер из корзины Spring Boot. Никакого
    // spring-boot-starter-data-jpa: constitution III.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Jackson с поддержкой Kotlin: разбор и запись значений настроек фильма
    // (столбец jsonb) и разбор тела запроса в контроллере. Зависимость и так
    // приходит в корзине Spring Boot, но объявлена явно: без неё модуль молча
    // опирался бы на чужую транзитивную, и её отключение сломало бы сборку
    // не там, где об этом подумали.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Модуль времени: без него Jackson не умеет ни прочитать, ни отдать
    // java.time.Instant и отвечает 500 на любой ответ с датой. Нашлось на
    // сквозном прогоне: GET /api/movies отвечал 200 на пустом списке и
    // падал 500, как только фильм создавался и в ответе появлялась дата.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Имена модулей и контейнеров совпадают; имя jar-файла — тоже.
tasks.bootJar {
    archiveFileName.set("syp-admin-app.jar")
}
