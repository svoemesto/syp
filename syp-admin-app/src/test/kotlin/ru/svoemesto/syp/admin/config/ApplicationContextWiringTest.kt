package ru.svoemesto.syp.admin.config

import java.lang.reflect.Constructor
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RestController

/**
 * Проверка того, что каждый контроллер можно собрать из объявленных бинов.
 *
 * Тест ловит класс дефектов «проверка проходит, ничего не проверяя». Класс
 * написан, компилируется, тесты создают его руками — а бин в конфигурации не
 * объявлен, и приложение падает при старте. Именно так вышло с сервисом
 * доводки границ: компиляция, ktlint и CI были зелёными, а приложение не
 * стартовало, пока это не увидел живой стенд.
 *
 * Проверка статическая, без базы и хранилища: поднимать контекст здесь нельзя,
 * конфигурации читают подключение к базе, и проверка связывания упала бы на
 * инфраструктуре вместо своего предмета.
 */
class ApplicationContextWiringTest {
    /**
     * У каждого параметра конструктора контроллера есть объявленный бин.
     *
     * Отсутствующий бин означает, что приложение не стартует: Spring не найдёт
     * зависимость и выбросит «required a bean of type» при инициализации.
     *
     * @throws AssertionError если хоть один параметр не объявлен
     */
    @Test
    fun `параметры конструкторов контроллеров объявлены бинами`() {
        val declared = declaredBeanTypes()
        val missing = linkedMapOf<String, List<String>>()

        controllers().forEach { controller ->
            val constructor = singleConstructor(controller)
            val absent =
                constructor.parameterTypes
                    .map { it.name }
                    .filterNot { name -> declared.any { it.matchesName(name) } }
            if (absent.isNotEmpty()) {
                missing[controller.simpleName] = absent.map { it.substringAfterLast('.') }
            }
        }

        assertTrue(
            missing.isEmpty(),
            "эти зависимости контроллеров не объявлены бином, и приложение не стартует: $missing",
        )
    }

    /** Классы-контроллеры: то, что Spring делает компонентами HTTP-слоя. */
    private fun controllers(): List<Class<*>> =
        allClassNames()
            .mapNotNull { loadClass(it) }
            .filter { it.isAnnotationPresent(RestController::class.java) || it.isAnnotationPresent(Controller::class.java) }
            .filter { it.simpleName.endsWith("Controller") }

    /** Все типы, которые объявляют бин-методы. */
    private fun declaredBeanTypes(): List<Class<*>> =
        allClassNames()
            .mapNotNull { loadClass(it) }
            .filter { type -> type.declaredMethods.any { it.isAnnotationPresent(Bean::class.java) } }
            .flatMap { type -> type.declaredMethods.filter { it.isAnnotationPresent(Bean::class.java) }.map { it.returnType } }

    /** Имена всех классов бэкенда, разобранных из каталога сборки. */
    private fun allClassNames(): List<String> {
        val marker = javaClass.protectionDomain.codeSource.location.toURI()
        val root = java.io.File(marker)
        if (!root.isDirectory) {
            return emptyList()
        }
        return root
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath.removePrefix(root.absolutePath).removePrefix("/").removeSuffix(".class").replace('/', '.') }
            .toList()
    }

    /** Класс по имени либо `null`, если его нет. */
    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull()

    /** Конструктор класса: он должен быть один, иначе проверка неоднозначна. */
    private fun singleConstructor(type: Class<*>): Constructor<*> =
        type.declaredConstructors.singleOrNull()
            ?: error("у ${type.simpleName} конструкторов не ровно один — проверка не сможет выбрать нужный")

    /**
     * Подходит ли объявленный тип в качестве требуемого.
     *
     * Сравнение идёт по цепочке наследования, а не по простому совпадению
     * имени: бин может быть объявлен конкретным классом, а требоваться
     * интерфейс.
     */
    private fun Class<*>.matchesName(requiredName: String): Boolean {
        var current: Class<*>? = this
        while (current != null) {
            if (current.name == requiredName) return true
            if (current.isInterface && current.name == requiredName) return true
            current = current.superclass
        }
        return false
    }
}
