package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Проверка того, что ракурс лица не определяется (задача T070, FR-035).
 *
 * Определение ракурса — «анфас, профиль, три четверти» — в первый срез
 * **не входит**. Это не «пока не сделано», а сознательное решение: отдельный
 * детектор позы ошибается и стоит отдельного усилия, а первое требование
 * владельца о положении персонажа закрыто рамкой и размером плана (ADR-0003).
 *
 * Опасность такого решения не в том, что признака нет, а в том, что он может
 * появиться позже **незаметно**: один столбец `pose` или одно поле в ответе
 * добавят охотно, никто не сломает сборку, и через полгода интерфейс будет
 * показывать ракурс, который система не определяет и не проверяет.
 *
 * Поэтому проверка читает **исходники домена** и падает сама, если признак
 * ракурса в них появится. Она не проверяет значение, а проверяет отсутствие
 * возможности.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class NoFacePoseTest {
    /**
     * Слова, означающие ракурс.
     *
     * Список закрытый и намеренно узкий: «поза» в смысле «ракурс»,
     * «анфас», «профиль», «три четверти», «поворот головы» и названия
     * значений, которые могли бы появиться в перечислении.
     */
    private val forbiddenWords =
        listOf(
            "анфас",
            "профил",
            "три четверти",
            "четверти",
            "facepose",
            "face_pose",
            "pose_type",
            "headturn",
            "head_turn",
            "yaw",
            "pitch",
            "roll_angle",
        )

    /**
     * Слова, которые в исходниках встречаются и ракурса не означают.
     *
     * Без этого списка проверка падала бы на слове «ракурс» в комментарии
     * «ракурс лица не определяется» — то есть ровно на том тексте, который
     * фиксирует решение.
     */
    private val allowedInComments =
        listOf("ракурс", "поза")

    @Test
    fun `в домене персонажей нет признака ракурса`() {
        val root = locateSources()
        val files =
            Files
                .walk(root)
                .use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        assertTrue(files.isNotEmpty(), "исходники домена персонажей не найдены по пути $root")

        val offenders = mutableListOf<String>()
        files.forEach { file ->
            val text = Files.readString(file)
            forbiddenWords.forEach { word ->
                if (text.contains(word, ignoreCase = true)) {
                    offenders.add("${file.fileName}: найдено «$word»")
                }
            }
            // Отдельно ловим поле или столбец с названием ракурса: само слово
            // «pose» в любом виде не запрещено — запрещена его роль признака
            // лица.
            if (Regex("""\b(pose|turn|angle)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                val suspect =
                    text.lines().filter { line ->
                        Regex("""\b(pose|turn|angle)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                            allowedInComments.none { word -> line.contains(word, ignoreCase = true) }
                    }
                if (suspect.isNotEmpty()) {
                    offenders.add("${file.fileName}: ${suspect.first().trim()}")
                }
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Определение ракурса лица в первый срез не входит (FR-035), а в исходниках " +
                    "появился признак с этим смыслом:\n" + offenders.joinToString("\n") { "  $it" } +
                    "\nРакурс не определяется: рамка и размер плана его заменяют (ADR-0003)",
            )
        }
    }

    /**
     * Находит каталог исходников домена персонажей.
     *
     * @return каталог с файлами домена
     */
    private fun locateSources(): Path {
        val here = Paths.get("").toAbsolutePath()
        val candidates =
            listOf(
                here.resolve("src/main/kotlin/ru/svoemesto/syp/admin/characters"),
                here.resolve("syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/characters"),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: fail(
                "Каталог исходников домена персонажей не найден; проверялись " + candidates.joinToString(", "),
            )
    }
}
