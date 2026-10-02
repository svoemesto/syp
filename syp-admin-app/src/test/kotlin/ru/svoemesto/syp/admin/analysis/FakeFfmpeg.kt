package ru.svoemesto.syp.admin.analysis

import java.nio.file.Files
import java.nio.file.Path

/**
 * Подставная внешняя программа для проверок задания `ANALYZE`.
 *
 * Настоящий `ffmpeg` для проверок сбоя не годится: сбой должен быть
 * **воспроизводим**, а не зависеть от того, как именно упал настоящий
 * декодер, и проверка обязана идти на машине без видеокарты и без доступа к
 * архиву. Поэтому пишется маленький сценарий оболочки, который для кода
 * задания выглядит как `ffmpeg`: печатает тот же счётчик кадров и те же
 * строки оценок детектора, а код завершения выбирает файл настроек рядом с
 * собой.
 *
 * Сценарий различает фазы по аргументам: фильтр `scdet` — детекция границ,
 * `tile=` — укладка превью в листы. Благодаря этому устраивается ровно тот
 * сбой, который нужен проверке: детекция прошла, а листы не собрались.
 *
 * Настройки задаются файлом, а не переменной окружения: переменную пришлось
 * бы протягивать через всё исполнение задания, а она нужна только тесту.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object FakeFfmpeg {
    /** Имя файла настроек рядом со сценарием. */
    const val SETTINGS_FILE: String = "fake-ffmpeg.settings"

    /**
     * Пишет подставной `ffmpeg` во временный каталог и возвращает путь к нему.
     *
     * @param directory каталог для сценария
     * @param detectExit код завершения фазы детекции
     * @param sheetExit код завершения фазы изготовления листов
     * @param sheetCount сколько листов выдать в фазе изготовления
     * @return путь к исполняемому сценарию
     */
    fun write(
        directory: Path,
        detectExit: Int = 0,
        sheetExit: Int = 0,
        sheetCount: Int = 1,
    ): Path {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(SETTINGS_FILE),
            """
            detect=$detectExit
            sheet=$sheetExit
            count=$sheetCount
            """.trimIndent() + "\n",
        )
        val script = directory.resolve("fake-ffmpeg.sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            # Подставной ffmpeg для проверок задания ANALYZE. Выводит то же,
            # что настоящий: счётчик кадров и строки оценок детектора.
            settings=`dirname "${'$'}0"`/fake-ffmpeg.settings
            detect_exit=0
            sheet_exit=0
            count=1
            while IFS='=' read -r key value; do
                case "${'$'}key" in
                    detect) detect_exit="${'$'}value" ;;
                    sheet) sheet_exit="${'$'}value" ;;
                    count) count="${'$'}value" ;;
                esac
            done < "${'$'}settings"
            mode=unknown
            for argument in "${'$'}@"; do
                case "${'$'}argument" in
                    *scdet*) mode=detect ;;
                    *tile=*) mode=sheet ;;
                esac
            done
            output=""
            for argument in "${'$'}@"; do
                output="${'$'}argument"
            done
            case "${'$'}mode" in
                detect)
                    echo "frame=    10/1000 speed=1.0x"
                    echo "lavfi.scd.score: 20.0, lavfi.scd.time: 1.0"
                    echo "lavfi.scd.score: 6.0, lavfi.scd.time: 2.0"
                    echo "lavfi.scd.score: 30.0, lavfi.scd.time: 3.0"
                    exit "${'$'}detect_exit"
                    ;;
                sheet)
                    echo "frame=    10/1000 speed=1.0x"
                    index=0
                    while [ "${'$'}index" -lt "${'$'}count" ]; do
                        number=$(printf '%06d' "${'$'}index")
                        name=$(printf '%s' "${'$'}output" | sed "s/%06d/${'$'}number/")
                        printf 'fake-png-bytes' > "${'$'}name"
                        index=$((index + 1))
                    done
                    exit "${'$'}sheet_exit"
                    ;;
            esac
            echo "неизвестный режим работы подставной программы: ${'$'}mode" >&2
            exit 2
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }
}
