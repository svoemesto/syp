package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки согласованности сторон протокола программы эмбеддера (задача T062).
 *
 * Протокол описан дважды: один раз числами в Kotlin, другой раз форматом
 * `struct` в программе на Python. Ничто их не сверяло, и они разошлись:
 * Kotlin объявлял 28 байт на лицо, программа читала 30, а писалось 32. Расхождение
 * не выдавало себя ничем до первого лица, а всплывало как `BufferOverflowException`,
 * то есть виной декодера, который тут ни при чём.
 *
 * Проверка читает оба описания и сверяет их числами. Стороны описываются в разных
 * языках, поэтому проверка идёт по исходникам: формат `struct` и перечисления
 * Kotlin разбираются здесь, а не в рантайме, где нужен был бы живой процесс.
 */
class FaceEmbedderProtocolTest {
    /** Программа эмбеддера, описанная в исходнике на Python. */
    private val program: String =
        Path.of("src/main/resources/embedder/syp_face_embedder.py").toString()

    /** Читает программу эмбеддера целиком. */
    private fun programText(): String {
        val path = Path.of(program)
        check(Files.exists(path)) { "Программа эмбеддера не найдена по пути $program" }
        return Files.readString(path)
    }

    /** Размер структуры Python по её формату, в байтах. */
    private fun structSize(format: String): Int {
        // Порядок байт стоит в начале и места не занимает: в Python это
        // префикс вида `<` или `>`. Пропускаем только его, а не первый символ
        // вообще: иначе теряется первое поле записи.
        val body = format.trimStart('<', '>', '=', '!', '@')
        return buildList {
            body.forEach { code ->
                add(
                    when (code) {
                        'b', 'B', 'c' -> 1
                        'h', 'H' -> 2
                        'i', 'I', 'l', 'L', 'f' -> 4
                        'd', 'q', 'Q' -> 8
                        else -> throw IllegalArgumentException("Формат $format: неизвестен $code")
                    },
                )
            }
        }.sum()
    }

    /** Складывает слагаемые, каждое из которых — число или произведение чисел. */
    private fun sumOfTerms(expression: String): Int =
        expression.split('+').sumOf { term ->
            val factors = term.split('*').map { it.trim().toInt() }
            factors.reduce { product, factor -> product * factor }
        }

    /** Значение константы Kotlin целым числом, по её объявлению. */
    private fun kotlinConstant(name: String): Int {
        val text =
            Path
                .of("src/main/kotlin/ru/svoemesto/syp/admin/characters/FaceEmbedderProcess.kt")
                .let { Files.readString(it) }
        val declaration =
            Regex("const val $name: Int = (.+)")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?: throw IllegalStateException("Константа $name не объявлена")
        // В выражении допустимы только числа, сложение и умножение: иначе это
        // уже не размер записи, и сверять тут нечего.
        require(Regex("[0-9*+ ]+").matches(declaration)) {
            "Константа $name объявлена не суммой произведений: $declaration"
        }
        return sumOfTerms(declaration)
    }

    @Test
    fun `запись на лицо совпадает у обеих сторон`() {
        val format =
            Regex("""REQUEST_FACE = struct\.Struct\("([^"]+)" \+ "([^"]+)" \* (\d+)""")
                .find(programText())
                ?.let { match ->
                    "${match.groupValues[1]}${match.groupValues[2].repeat(match.groupValues[3].toInt())}"
                }
                ?: error("Формат записи лица в программе эмбеддера не найден")
        assertEquals(
            kotlinConstant("REQUEST_FACE_BYTES"),
            structSize(format),
            "Размер записи на лицо разошёлся: Kotlin объявляет столько, " +
                "программа читает столько",
        )
    }

    @Test
    fun `заголовок запроса и число лиц совпадают с тем, что пишет сторона Kotlin`() {
        val header =
            Regex("""REQUEST = struct\.Struct\("<([^"]+)"\)""")
                .find(programText())
                ?.groupValues
                ?.get(1)
                ?: error("Формат заголовка запроса в программе эмбеддера не найдена")
        assertEquals(
            kotlinConstant("REQUEST_HEADER_BYTES"),
            structSize(header),
            "Размер заголовка запроса разошёлся между сторонами",
        )
        val count =
            Regex("""COUNT = struct\.Struct\("<([^"]+)"\)""")
                .find(programText())
                ?.groupValues
                ?.get(1)
                ?: error("Формат числа лиц в программе эмбеддера не найдена")
        assertEquals(
            kotlinConstant("REQUEST_COUNT_BYTES"),
            structSize(count),
            "Размер числа лиц разошёлся между сторонами",
        )
    }

    @Test
    fun `программа читает кадр, а не только лица`() {
        // Без кадра эмбеддер считать нечего: признак считается по пикселям лица.
        // Проверяем, что сторона Python ждёт именно кадр.
        assertTrue(
            programText().contains("def read_frame(") &&
                programText().contains("read_frame(stream"),
            "Программа эмбеддера не читает кадр из запроса",
        )
    }
}
