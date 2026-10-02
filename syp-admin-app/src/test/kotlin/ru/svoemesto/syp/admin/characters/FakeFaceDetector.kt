package ru.svoemesto.syp.admin.characters

import java.nio.file.Files
import java.nio.file.Path

/**
 * Подставная программа детектора лиц для проверок протокола.
 *
 * Настоящая программа детектора — Python с нейросетевой средой, и для
 * проверок протокола она не годится: нужна машина без видеокарты, а проверять
 * надо **сбой**, который иначе не воспроизвести, — оборванный ответ, ненулевой
 * код завершения, потерю текста ошибки и молчание вместо ответа. Подставная
 * программа говорит на том же протоколе и выдаёт ровно тот сценарий, который
 * задан проверке.
 *
 * Протокол — тот же, что у настоящей программы: 16 байт заголовка кадра, затем
 * байты кадра на вход; номер кадра, время и рамки на выход. Расхождение
 * протокола здесь означало бы, что проверка проверяет не то, что работает.
 *
 * Поведение задаётся файлом настроек рядом со сценарием, а не переменной
 * окружения: переменную пришлось бы протягивать через весь путь программы, а
 * она нужна только проверке.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object FakeFaceDetector {
    /** Имя файла настроек рядом со сценарием. */
    const val SETTINGS_FILE: String = "fake-face-detector.settings"

    /** Сценарий: отвечает на каждый кадр одной рамкой. */
    const val MODE_NORMAL: String = "normal"

    /** Сценарий: на заданном кадре завершается с ненулевым кодом. */
    const val MODE_FAIL: String = "fail"

    /** Сценарий: на заданном кадре отвечает номером чужого кадра. */
    const val MODE_WRONG_NUMBER: String = "wrong-number"

    /** Сценарий: на заданном кадре не отвечает вовсе. */
    const val MODE_SILENT: String = "silent"

    /** Сценарий: на заданном кадре обрывает ответ на середине. */
    const val MODE_TRUNCATED: String = "truncated"

    /**
     * Пишет подставную программу во временный каталог и возвращает путь к ней.
     *
     * @param directory каталог для сценария
     * @param mode сценарий поведения
     * @param failAt номер кадра, на котором сценарий срабатывает
     * @param faceX1 левая граница выдаваемой рамки
     * @param faceY1 верхняя граница выдаваемой рамки
     * @param faceX2 правая граница выдаваемой рамки
     * @param faceY2 нижняя граница выдаваемой рамки
     * @param confidence уверенность выдаваемой рамки
     * @param shiftByKind на сколько пикселей сдвинуть рамку у всех видов
     *   модели, кроме первого: так подставная программа отвечает на ту же
     *   эпизод по-разному в зависимости от того, какая модель поднята
     * @param exit код завершения при сбое
     * @param stderrText текст, который программа пишет в поток ошибок
     * @return путь к исполняемому сценарию
     */
    @Suppress("LongParameterList")
    fun write(
        directory: Path,
        mode: String = MODE_NORMAL,
        failAt: Int = 2,
        faceX1: Int = 0,
        faceY1: Int = 0,
        faceX2: Int = 3,
        faceY2: Int = 1,
        confidence: Double = 0.9,
        shiftByKind: Int = 0,
        exit: Int = 3,
        stderrText: String = "подставной детектор: сценарий $mode на кадре $failAt",
    ): Path {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(SETTINGS_FILE),
            """
            mode=$mode
            fail_at=$failAt
            x1=$faceX1
            y1=$faceY1
            x2=$faceX2
            y2=$faceY2
            confidence=$confidence
            shift_by_kind=$shiftByKind
            exit=$exit
            text=$stderrText
            """.trimIndent() + "\n",
        )
        val script = directory.resolve("fake-face-detector.py")
        Files.writeString(script, scriptOf())
        script.toFile().setExecutable(true)
        return script
    }

    /**
     * Текст подставной программы.
     *
     * @return содержимое сценария
     */
    private fun scriptOf(): String =
        """
        #!/usr/bin/env python3
        # Подставная программа детектора лиц: тот же протокол, что у настоящей,
        # но сценарий сбоя задаётся настройками рядом.
        import json, os, struct, sys, time

        settings = {}
        here = os.path.dirname(os.path.abspath(__file__))
        with open(os.path.join(here, "$SETTINGS_FILE"), encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                settings[key] = value

        # Вид и путь модели приходят аргументами командной строки — так же,
        # как у настоящей программы: проверка переключения модели обязана
        # видеть то, что передаёт бэкенд.
        argv = sys.argv[1:]
        given = dict(zip(argv[0::2], argv[1::2]))
        model_kind = given.get("--model-kind", "yunet")
        model_path = given.get("--model", "/opt/syp/models/fake.onnx")

        # Ответ зависит от вида модели: сдвиг на applying_shift пикселей.
        applying_shift = int(settings.get("shift_by_kind", "0"))
        shift = 0 if model_kind == "yunet" else applying_shift

        out = sys.stdout.buffer
        out.write(json.dumps({
            "status": "ready",
            "model": model_path,
            "model_kind": model_kind,
            "provider": "CPUExecutionProvider",
            "onnxruntime": "0.0.0",
            "numpy": "0.0.0",
            "input": [640, 640],
            "score_threshold": 0.9,
            "nms_threshold": 0.3,
        }, ensure_ascii=False).encode("utf-8") + b"\n")
        out.flush()

        header = struct.Struct("<IIII")
        answer_header = struct.Struct("<IfI")
        answer_face = struct.Struct("<hhhhf")
        bytes_per_pixel = {1: 3, 2: 3, 3: 1}
        mode = settings.get("mode", "normal")
        fail_at = int(settings.get("fail_at", "2"))
        face = (
            int(settings.get("x1", "0")),
            int(settings.get("y1", "0")),
            int(settings.get("x2", "3")),
            int(settings.get("y2", "1")),
            float(settings.get("confidence", "0.9")),
        )

        while True:
            head = sys.stdin.buffer.read(header.size)
            if not head:
                raise SystemExit(0)
            if len(head) != header.size:
                raise SystemExit(9)
            number, width, height, pixel_format = header.unpack(head)
            remaining = width * height * bytes_per_pixel[pixel_format]
            while remaining > 0:
                chunk = sys.stdin.buffer.read(min(remaining, 1 << 20))
                if not chunk:
                    raise SystemExit(9)
                remaining -= len(chunk)
            if number == fail_at:
                if mode == "fail":
                    print(settings.get("text", "сбой"), file=sys.stderr, flush=True)
                    raise SystemExit(int(settings.get("exit", "3")))
                if mode == "silent":
                    # Молчит, но честно: ждёт следующего заголовка и выходит,
                    # когда бэкенд закрыл поток. Бесконечный сон здесь означал
                    # бы, что проверка минуту ждёт разрушения процесса вместо
                    # самого таймаута кадра.
                    sys.stdin.buffer.read()
                if mode == "truncated":
                    out.write(answer_header.pack(number, 1.0, 1) + bytes([0, 0]))
                    out.flush()
                    raise SystemExit(int(settings.get("exit", "4")))
            reported = number + 1000 if (mode == "wrong-number" and number == fail_at) else number
            shifted = (face[0] + shift, face[1], face[2] + shift, face[3], face[4])
            out.write(answer_header.pack(reported, 1.5, 1) + answer_face.pack(*shifted))
            out.flush()
        """.trimIndent() + "\n"
}
