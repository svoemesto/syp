#!/usr/bin/env python3
# SYP — стенд замера детектора лиц (задача T076, замер М-02).
#
# Зачем стенд отдельный от программы детектора. Программа детектора отвечает
# на кадр рамками; стенд отвечает на вопрос «сколько времени стоит серия и
# какие рамки получились» и пишет их в файл, который можно разобрать позже.
# Разбор идёт по сохранённому результату, а не по памяти агента: иначе через
# месяц нельзя проверить ни одно число отчёта.
#
# Стенд работает **внутри контейнера админки**: та же среда исполнения, тот же
# провайдер вычислений и тот же канал кадров, что и в задании `FACES`. На
# машине администратора провайдер onnxruntime молча уходит на CPU, и замер
# скорости на ней означал бы замер не того, что работает в стеке.
#
# Кадры идут из ffmpeg тем же фильтром, что и выборка замера: каждый 89-й
# кадр серии. Стенд не пишет ни одного файла кадра на диск — кадр идёт в
# программу детектора через стандартный ввод (ADR-0010, ограничение 1).
#
# Запуск (внутри контейнера syp-admin-app):
#   python3 /tmp/t076/bench-face-detector.py \
#       --model /tmp/t076/models/<файл>.onnx --model-kind <вид> \
#       --series /sources/GOT.S01/GOT.S01E01.BDRip.1080p.mkv \
#       --runs 3 --out /tmp/t076/result-<имя>.json

from __future__ import annotations

import argparse
import json
import struct
import subprocess
import sys
import time

HEADER = struct.Struct("<IIII")
ANSWER_HEADER = struct.Struct("<IfI")
ANSWER_FACE = struct.Struct("<hhhhf")
FORMAT_BGR24 = 1
BYTES_PER_PIXEL = {FORMAT_BGR24: 3}

# Кадр серии: 1920 на 1080 пикселей по три байта.
FRAME_BYTES = 1920 * 1080 * 3


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Разбирает аргументы стенда."""
    parser = argparse.ArgumentParser(description="Стенд замера детектора лиц SYP")
    parser.add_argument("--model", required=True, help="путь к файлу модели ONNX")
    parser.add_argument("--model-kind", default="yunet", help="вид модели")
    parser.add_argument("--program", default="/opt/syp/face-detector/bin/syp-face-detector")
    parser.add_argument("--provider", default="CUDAExecutionProvider")
    parser.add_argument("--input-width", type=int, default=640)
    parser.add_argument("--input-height", type=int, default=640)
    parser.add_argument("--score-threshold", type=float, default=0.3)
    parser.add_argument("--nms-threshold", type=float, default=0.4)
    parser.add_argument("--top-k", type=int, default=300, help="сколько лучших рамок разбирать")
    parser.add_argument("--series", required=True, help="файл серии")
    parser.add_argument(
        "--frames-file",
        default="",
        help="файл сырых кадров выборки; пусто — кадры идут из декодера",
    )
    parser.add_argument("--step", type=int, default=89, help="шаг выборки кадров")
    parser.add_argument("--limit", type=int, default=0, help="ограничить число кадров, 0 — без ограничения")
    parser.add_argument("--offset", type=int, default=0, help="с какой выборки начать, индекс кратен шагу")
    parser.add_argument("--runs", type=int, default=3, help="сколько раз прогнать одну и ту же выборку")
    parser.add_argument("--out", required=True, help="куда писать результат")
    return parser.parse_args(argv)


def start_detector(args: argparse.Namespace) -> tuple[subprocess.Popen, dict]:
    """Поднимает программу детектора и читает её строку приветствия."""
    process = subprocess.Popen(  # noqa: S603 — путь и аргументы из конфигурации стенда
        [
            args.program,
            "--model",
            args.model,
            "--model-kind",
            args.model_kind,
            "--provider",
            args.provider,
            "--input-width",
            str(args.input_width),
            "--input-height",
            str(args.input_height),
            "--score-threshold",
            str(args.score_threshold),
            "--nms-threshold",
            str(args.nms_threshold),
            "--top-k",
            str(args.top_k),
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    line = process.stdout.readline()
    if not line:
        error = process.stderr.read().decode("utf-8", "replace")
        raise SystemExit("Программа детектора не ответила: %s" % error)
    return process, json.loads(line.decode("utf-8"))


def read_exact(stream, count: int) -> bytes:
    """Читает ровно указанное число байт из потока."""
    chunks = []
    remaining = count
    while remaining > 0:
        chunk = stream.read(remaining)
        if not chunk:
            raise SystemExit("Поток программы закрыт на %d байт из %d" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def frames(args: argparse.Namespace):
    """Отдаёт пары «номер кадра, байты кадра» — из файла выборки или декодера.

    Два источника дают одинаковые байты: файл выборки нарезан тем же
    фильтром декодера. Файл быстрее в десятки раз, и на замере скорости это
    единственный способ отделить время детектора от времени декодирования.

    :param args: разобранные аргументы
    :return: генератор пар «номер, байты»
    """
    if args.frames_file:
        import os

        size = os.path.getsize(args.frames_file)
        total = size // FRAME_BYTES
        with open(args.frames_file, "rb") as source:
            for index in range(args.offset, total):
                if args.limit and index - args.offset >= args.limit:
                    break
                data = source.read(FRAME_BYTES)
                if len(data) < FRAME_BYTES:
                    break
                yield index * args.step, data
        return
    command = [
        "ffmpeg",
        "-nostdin",
        "-v",
        "error",
        "-i",
        args.series,
        "-vf",
        "select='not(mod(n\\,%d))'" % args.step,
        "-fps_mode",
        "passthrough",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "bgr24",
        "-",
    ]
    decoder = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    index = 0
    try:
        while True:
            if args.limit and index >= args.limit:
                break
            data = decoder.stdout.read(FRAME_BYTES)
            if len(data) < FRAME_BYTES:
                break
            if index >= args.offset:
                yield args.offset + index, data
            index += 1
    finally:
        decoder.stdout.close()
        decoder.terminate()
        decoder.wait()


def run_once(args: argparse.Namespace) -> dict:
    """Один прогон выборки: подъём программы, кадры, рамки, время."""
    process, greeting = start_detector(args)
    started = time.perf_counter()
    result = {"greeting": greeting, "frames": []}
    try:
        for number, data in frames(args):
            process.stdin.write(HEADER.pack(number, 1920, 1080, FORMAT_BGR24))
            process.stdin.write(data)
            process.stdin.flush()
            head = read_exact(process.stdout, ANSWER_HEADER.size)
            frame_number, elapsed_ms, count = ANSWER_HEADER.unpack(head)
            if frame_number != number:
                raise SystemExit("Ответ на кадр %d, а ждали %d" % (frame_number, number))
            payload = read_exact(process.stdout, count * ANSWER_FACE.size)
            faces = []
            for offset in range(0, len(payload), ANSWER_FACE.size):
                left, top, right, bottom, score = ANSWER_FACE.unpack_from(payload, offset)
                faces.append([left, top, right, bottom, round(float(score), 5)])
            result["frames"].append(
                {"n": number, "ms": round(float(elapsed_ms), 2), "faces": faces}
            )
        wall = time.perf_counter() - started
    finally:
        process.stdin.close()
        process.wait(timeout=120)
    detector_ms = [item["ms"] for item in result["frames"]]
    result["wall_seconds"] = round(wall, 3)
    result["count"] = len(result["frames"])
    result["detector_fps"] = round(len(detector_ms) / (sum(detector_ms) / 1000.0), 2) if detector_ms else 0.0
    result["wall_fps"] = round(len(detector_ms) / wall, 2) if wall else 0.0
    result["faces_total"] = sum(len(item["faces"]) for item in result["frames"])
    return result


def main(argv: list[str]) -> int:
    """Прогоняет выборку заданное число раз и пишет результат."""
    args = parse_args(argv)
    runs = []
    for index in range(args.runs):
        single = run_once(args)
        runs.append(single)
        print(
            "прогон %d: кадров %d, кадров/с детектора %.1f, с учётом декодирования %.1f, рамок %d"
            % (
                index + 1,
                single["count"],
                single["detector_fps"],
                single["wall_fps"],
                single["faces_total"],
            ),
            flush=True,
        )
    payload = {
        "model": args.model,
        "model_kind": args.model_kind,
        "provider": args.provider,
        "input": [args.input_width, args.input_height],
        "score_threshold": args.score_threshold,
        "nms_threshold": args.nms_threshold,
        "step": args.step,
        "offset": args.offset,
        "limit": args.limit,
        "runs": runs,
    }
    with open(args.out, "w", encoding="utf-8") as target:
        json.dump(payload, target, ensure_ascii=False)
    print("записано: %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
