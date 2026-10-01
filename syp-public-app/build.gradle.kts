// SYP — бэкенд публичной части.
//
// У модуля **нет исполнителя заданий** — ни класса воркера, ни зависимости от
// очереди (FR-085, research.md Т-20). Это структурная гарантия того, что
// собрать подборку за пользователя нечем, а не запрет соглашением, который
// можно случайно обойти. Проверяется задачей T027 и guard
// `tools/check-no-job-worker.sh`.
//
// Правило проверяется **самой сборкой**: ни один из модулей воркера,
// очереди и внешних программ не подключается. Попытка собрать подборку на
// сервере не просто запрещена, а структурно невозможна — кода, который её
// выполнял бы, в модуле нет. Исключение — `syp-core`, подключаемый целиком
// ради доступа к базе и канонизации сценария: там же нет ни исполнителя
// заданий, ни реестра артефактов, ни запуска внешних программ.
//
// Строки ниже — не украшение, а исполняемая проверка: если в `syp-core`
// появится исполнитель заданий, сборка упадёт.
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencies {
    // Общий модуль подключается целиком ради доступа к базе, канонизации и
    // подписи сценария (ADR-0011). Исполнителя заданий в нём нет: класс
    // воркера живёт в `syp-admin-app`, а этот модуль его не подключает.
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

// Проверка отсутствия исполнителя заданий. Упадёт на этапе разрешения
// зависимостей, то есть до того, как модуль может быть собран в образ.
// Текст ошибки объясняет причину: без него падение выглядело бы загадкой.
val checkNoJobWorker by tasks.registering {
    group = "verification"
    description = "Проверка: у публичного бэкенда нет исполнителя заданий (FR-085)"

    val forbiddenSources =
        files(
            fileTree("src/main/kotlin") {
                include("**/*JobWorker*.kt", "**/*Worker*.kt", "**/*Scheduler*.kt")
            },
        )

    doLast {
        val problems = mutableListOf<String>()

        forbiddenSources.files.filter { it.isFile }.forEach {
            problems.add("источник исполнителя заданий: ${it.path}")
        }

        // Проверяются разрешённые зависимости, а не текст файла сборки:
        // иначе проверка ловила бы саму себя — список запрещённых имён.
        val forbiddenModules =
            setOf(
                "spring-boot-starter-amqp",
                "spring-boot-starter-activemq",
                "spring-boot-starter-quartz",
                "spring-boot-starter-batch",
                "quartz",
            )
        configurations
            .getByName("runtimeClasspath")
            .resolvedConfiguration
            .firstLevelModuleDependencies
            .forEach { module ->
                val name = module.moduleName
                if (name in forbiddenModules) {
                    problems.add("зависимость исполнителя заданий: $name")
                }
            }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "FR-085 нарушен: у публичного бэкенда не должно быть исполнителя " +
                    "заданий (research.md Т-20, ADR-0009). Найдено — " +
                    problems.joinToString("; "),
            )
        }
        logger.lifecycle("Проверка FR-085 пройдена: исполнителя заданий у модуля нет")
    }
}

tasks.named("compileKotlin") { dependsOn(checkNoJobWorker) }
