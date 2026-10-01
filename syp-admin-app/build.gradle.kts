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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Имена модулей и контейнеров совпадают; имя jar-файла — тоже.
tasks.bootJar {
    archiveFileName.set("syp-admin-app.jar")
}
