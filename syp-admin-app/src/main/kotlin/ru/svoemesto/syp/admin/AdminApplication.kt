package ru.svoemesto.syp.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Точка входа админского бэкенда SYP.
 *
 * Здесь, в отличие от публичной части, **есть** исполнитель заданий: он берёт
 * виды `ANALYZE`, `FACES`, `TRAIN`, `HASH` (research.md Т-20). Вида `ASSEMBLE`
 * не существует — видео на сервере не собирается (ADR-0009).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@SpringBootApplication
class AdminApplication

/**
 * Запуск админского бэкенда.
 *
 * @param args аргументы командной строки Spring Boot
 */
fun main(args: Array<String>) {
    runApplication<AdminApplication>(*args)
}
