package ru.svoemesto.syp.public

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Точка входа публичного бэкенда SYP.
 *
 * Исполнителя заданий у модуля **нет** — ни класса, ни зависимости от очереди
 * (FR-085, research.md Т-20). Пользователь сам инициирует сборку подборки на
 * своей машине по сценарию, выданному здесь (ADR-0009).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@SpringBootApplication
class PublicApplication

/**
 * Запуск публичного бэкенда.
 *
 * @param args аргументы командной строки Spring Boot
 */
fun main(args: Array<String>) {
    runApplication<PublicApplication>(*args)
}
