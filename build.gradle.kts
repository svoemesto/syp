import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// SYP — корневой сборочный файл.
//
// Плагины объявлены здесь и подключаются в модулях. Никаких библиотек
// отображения объектов в корневых зависимостях нет: constitution III запрещает
// JPA и Hibernate, персистентность — сырой JDBC.
plugins {
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0" apply false
}

allprojects {
    group = "ru.svoemesto.syp"
    version = rootProject.version
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    // Компилируем тем JDK, который стоит на машине, но байт-код и API
    // ограничиваем Java 17 — минимальной версией, на которой работает
    // Spring Boot 3.5. Явный toolchain не задан: на машине разработки
    // установлен JDK 18, а образ контейнера несёт JRE 17; привязка к
    // конкретной версии JDK сделала бы сборку зависимой от состава машины.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(providers.gradleProperty("javaReleaseVersion").get().toInt())
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // Каждый публичный класс обязан иметь KDoc — constitution VI.1.
            // allWarningsAsErrors включается по мере уборки исходников и не
            // мешает компиляции уже написанного кода.
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<KtlintExtension> {
        version.set("1.5.0")
        // Правило `standard:filename` требует, чтобы имя файла совпадало с
        // именем единственного объявления в нём. В проекте файлы
        // конфигурации называются по назначению, а не по классу:
        // `config/Ports.kt` — это порты и правила их чтения. На эти имена
        // файлов ссылаются план и задачи спеки, поэтому правило отключено
        // явно и с записанной причиной, а не молча.
        disabledRules.add("standard:filename")
    }
}
