package ru.svoemesto.syp.admin.characters

import java.nio.file.Files
import java.nio.file.Path

/**
 * Подставной декодер для проверок задания `FACES`.
 *
 * Настоящий `ffmpeg` для этих проверок не годится по двум причинам: он
 * нужен на машине без видеокарты и без доступа к архиву, а проверять надо
 * **сбой**, который иначе не воспроизвести: оборванный кадр, ненулевой код
 * завершения и потерю текста ошибки. Подставной декодер выдаёт ровно то,
 * что нужно проверке, — ни больше, ни меньше.
 *
 * Сценарий оболочки для кода задания выглядит как `ffmpeg`: параметры те же,
 * кадры идут в стандартный вывод, текст ошибки — в стандартный вывод ошибок.
 *
 * Настройки задаются файлом рядом со сценарием, а не переменной окружения:
 * переменную пришлось бы протягивать через всё исполнение задания, а она
 * нужна только проверке.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object FakeDecoder {
    /** Имя файла настроек рядом со сценарием. */
    const val SETTINGS_FILE: String = "fake-decoder.settings"

    /**
     * Пишет подставной декодер во временный каталог и возвращает путь к нему.
     *
     * @param directory каталог для сценария
     * @param frames сколько целых кадров выдать
     * @param truncateBytes сколько байт недописать в последний кадр: ноль —
     *   поток обрывается на середине кадра
     * @param exit код завершения декодера
     * @param frameWidth ширина кадра в настройках декодера
     * @param frameHeight высота кадра в настройках декодера
     * @param stderrText текст, который декодер пишет в поток ошибок
     * @param hangSeconds сколько секунд декодер молчит, оставаясь живым: он
     *   ничего не пишет в стандартный вывод и спит — ровно то состояние, при
     *   котором задание раньше висело в состоянии «идёт работа» (T-189)
     * @return путь к исполняемому сценарию
     */
    fun write(
        directory: Path,
        frames: Int = 4,
        truncateBytes: Int = 0,
        exit: Int = 0,
        frameWidth: Int = 4,
        frameHeight: Int = 2,
        stderrText: String = "подставной декодер: кадров $frames",
        hangSeconds: Int = 0,
    ): Path {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(SETTINGS_FILE),
            """
            frames=$frames
            truncate=$truncateBytes
            exit=$exit
            width=$frameWidth
            height=$frameHeight
            text=$stderrText
            hang=$hangSeconds
            """.trimIndent() + "\n",
        )
        val script = directory.resolve("fake-decoder.sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            # Подставной декодер для проверок задания FACES: пишет кадры в
            # стандартный вывод, текст ошибки — в стандартный вывод ошибок.
            settings=`dirname "${'$'}0"`/fake-decoder.settings
            frames=0
            truncate=0
            exit_code=0
            width=4
            height=2
            text=""
            hang=0
            while IFS='=' read -r key value; do
                case "${'$'}key" in
                    frames) frames="${'$'}value" ;;
                    truncate) truncate="${'$'}value" ;;
                    exit) exit_code="${'$'}value" ;;
                    width) width="${'$'}value" ;;
                    height) height="${'$'}value" ;;
                    text) text="${'$'}value" ;;
                    hang) hang="${'$'}value" ;;
                esac
            done < "${'$'}settings"
            if [ "${'$'}hang" -gt 0 ]; then
                # Номера процессов пишутся только когда декодер молчит: иначе
                # проверка «за проход не появляется ни одного файла» видела бы
                # служебные файлы проверки.
                echo ${'$'}${'$'} > "$(dirname "${'$'}0")/fake-decoder.pid"
                # Молчит отдельный дочерний процесс: он держит конец трубы, и
                # убийство одного декодера чтение кадров не разблокирует —
                # ровно так ведёт себя программа, запускающая помощников.
                printf 'подставной декодер: молчу %s с, не отдавая ни байта\n' "${'$'}hang" >&2
                sleep "${'$'}hang" &
                echo ${'$'}! > "$(dirname "${'$'}0")/fake-decoder.child.pid"
                wait
            fi
            # Один «кадр» — 3 байта на пиксель, как в bgr24.
            frame_bytes=${'$'}(( 3 * width * height ))
            number=0
            while [ "${'$'}number" -lt "${'$'}frames" ]; do
                i=0
                while [ "${'$'}i" -lt "${'$'}frame_bytes" ]; do
                    printf '\001'
                    i=${'$'}(( i + 1 ))
                done
                number=${'$'}(( number + 1 ))
            done
            if [ "${'$'}truncate" -gt 0 ]; then
                i=0
                while [ "${'$'}i" -lt "${'$'}truncate" ]; do
                    printf '\002'
                    i=${'$'}(( i + 1 ))
                done
            fi
            printf '%s\n' "${'$'}text" >&2
            exit "${'$'}exit_code"
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }
}
