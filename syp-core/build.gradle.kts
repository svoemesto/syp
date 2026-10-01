// SYP — общий модуль `syp-core`.
//
// Модуль не содержит состояния: ни базы, ни очереди, ни файловой системы
// (ADR-0011, последствие 2). Здесь только то, что одинаково нужно обоим
// бэкендам: доступ к базе сырым JDBC, очередь заданий, запуск внешних программ,
// листы превью, канонизация и подпись сценария, общие коды ошибок.
//
// constitution III: ни одной библиотеки отображения объектов. Проверяется
// задачей T002 и guard `tools/check-no-jpa-imports.sh`.
plugins {
    id("java-library")
}

dependencies {
    // Драйвер PostgreSQL — единственная зависимость доступа к базе.
    api("org.postgresql:postgresql:${rootProject.property("postgresDriverVersion")}")

    // Клиент объектного хранилища: листы превью, модели, канонические байты
    // сценариев лежат в MinIO (`docs/system/02-containers.md`).
    api("io.minio:minio:${rootProject.property("minioClientVersion")}")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Запрещённые зависимости объявлены явно: их появление должно быть
// немедленно заметно при разрешении зависимостей, а не на ревью.
configurations.configureEach {
    exclude(group = "org.hibernate")
    exclude(group = "org.hibernate.orm")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")
}
