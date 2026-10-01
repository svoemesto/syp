// SYP — бэкенд публичной части.
//
// У модуля **нет исполнителя заданий** — ни класса воркера, ни зависимости от
// очереди (FR-085, research.md Т-20). Это структурная гарантия того, что
// собрать подборку за пользователя нечем, а не запрет соглашением, который
// можно случайно обойти. Проверяется задачей T027 и guard
// `tools/check-no-job-worker.sh`.
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":syp-core"))

    // Только web и JDBC-драйвер. Никакого starter-data-jpa (constitution III).
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("syp-public-app.jar")
}
