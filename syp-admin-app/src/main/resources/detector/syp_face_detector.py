#!/usr/bin/env python3
# SYP — детектор лиц на видеокарте.
#
# Программа живёт внутри контейнера `syp-admin-app` и запускается оттуда же
# бэкендом: путь к ней приходит из конфигурации развёртывания, секретов в
# ней нет, версии среды исполнения зафиксированы в образе (ADR-0010,
# ограничения 1—4).
#
# Ограничения внешней программы, которые здесь соблюдаются (ADR-0010):
#   1. программа не умеет читать и писать файлы: кадр приходит через
#      стандартный ввод, результат уходит через стандартный вывод;
#   2. путь к программе и к модели фиксированы конфигурацией, а не заданием;
#   3. запускается только из контейнера очереди;
#   4. ненулевой код завершения означает отказ, а не «нулевой результат»;
#   5. сборкой видео программа не занимается.
#
# Протокол обмена. На старте программа печатает одну строку JSON с
# описанием себя и переходит в цикл чтения кадров:
#
#   на вход  : 16 байт заголовка (номер кадра, ширина, высота, формат —
#              четыре числа uint32 little-endian), затем сами байты кадра;
#   на выход : uint32 номер кадра, float32 время вывода в миллисекундах,
#              uint32 число лиц, затем на каждое лицо четыре int16
#              (x1, y1, x2, y2) и float32 уверенность.
#
# Рамки приходят в координатах кадра полного разрешения, обрезанные по его
# границам: проверка «рамка внутри кадра» на стороне бэкенда тогда всегда
# осмысленна (ADR-0001, FR-034).
#
# Ошибка на любом кадре — это ошибка всего прохода: программа пишет в поток
# ошибок текст причины и завершается с ненулевым кодом. Молчаливый
# частичный результат запрещён (SC-005, FR-092).

from __future__ import annotations

import argparse
import json
import struct
import sys
import time
from typing import Any

import numpy as np
import onnxruntime as ort

# Коды формата кадра в заголовке. Значения фиксированы: их читает бэкенд.
FORMAT_BGR24 = 1
FORMAT_RGB24 = 2
FORMAT_GRAY = 3

# Число байт на пиксель по коду формата.
BYTES_PER_PIXEL = {FORMAT_BGR24: 3, FORMAT_RGB24: 3, FORMAT_GRAY: 1}

# Заголовок кадра: номер, ширина, высота, формат.
HEADER = struct.Struct("<IIII")

# Ответ на кадр: номер, время вывода, число лиц.
ANSWER_HEADER = struct.Struct("<IfI")

# Одно лицо: четыре координаты и уверенность.
ANSWER_FACE = struct.Struct("<hhhhf")

# Шаги сетки YuNet. Рамка выводится в единицах шага, поэтому при разборе
# умножается на четыре: так устроена сама модель, а не наш выбор.
STRIDES = (8, 16, 32)


def fail(message: str) -> "None":
    """Печатает причину отказа в поток ошибок и завершает программу.

    Код завершения ненулевой: частичный результат не выдаётся за полный
    (SC-005, FR-092).
    """
    print(message, file=sys.stderr, flush=True)
    raise SystemExit(1)


def read_exact(stream: Any, count: int) -> bytes:
    """Читает ровно `count` байт или поднимает отказ.

    Обычное чтение из канала возвращает столько, сколько успело, поэтому
    «прочитал сколько вышло» здесь означало бы молчаливо испорченный кадр.

    :param stream: поток стандартного ввода
    :param count: сколько байт требуется
    :return: буфер нужной длины
    """
    chunks: list[bytes] = []
    remaining = count
    while remaining > 0:
        chunk = stream.read(remaining)
        if not chunk:
            fail("Детектор: поток кадров закрыт на %d байт из %d" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def resize_area(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Уменьшает кадр усреднением по площади.

    Усреднение по площади, а не выборка пикселя: при уменьшении втрое
    усреднение сохраняет мелкие лица, а выборка их теряет, и потерянное лицо
    не вернуть — второй проход по кадру не предусмотрен (ADR-0002).

    :param image: кадр HxWxC в порядке BGR
    :param width: ширина результата
    :param height: высота результата
    :return: кадр height x width x C
    """
    source_h, source_w = image.shape[0], image.shape[1]
    if source_h == height and source_w == width:
        return image
    values = image.astype(np.float64)
    if values.ndim == 2:
        values = values[:, :, None]
    # Сумма по прямоугольнику считается интегральной картой: за один проход,
    # без умножения матриц на каждый пиксель.
    integral = np.zeros((source_h + 1, source_w + 1, values.shape[2]), dtype=np.float64)
    integral[1:, 1:, :] = np.cumsum(np.cumsum(values, axis=0), axis=1)
    # Границы каждой ячейки результата в координатах исходника.
    ys = np.linspace(0, source_h, height + 1)
    xs = np.linspace(0, source_w, width + 1)
    y0 = np.floor(ys[:-1]).astype(np.int64)
    y1 = np.ceil(ys[1:]).astype(np.int64)
    x0 = np.floor(xs[:-1]).astype(np.int64)
    x1 = np.ceil(xs[1:]).astype(np.int64)
    y0 = np.minimum(y0, source_h - 1)
    x0 = np.minimum(x0, source_w - 1)
    areas = ((y1 - y0).astype(np.float64) * (x1 - x0).astype(np.float64))[:, None, None]
    # Сумма по ячейке: integral[y1, x1] - integral[y0, x1] - integral[y1, x0] + integral[y0, x0]
    block = (
        integral[y1[:, None], x1[None, :], :]
        - integral[y0[:, None], x1[None, :], :]
        - integral[y1[:, None], x0[None, :], :]
        + integral[y0[:, None], x0[None, :], :]
    )
    return (block / areas).astype(np.float32)


def non_maximum_suppression(
    boxes: np.ndarray,
    scores: np.ndarray,
    threshold: float,
) -> list[int]:
    """Оставляет по одной рамке на лицо.

    :param boxes: рамки в формате x1, y1, x2, y2
    :param scores: уверенность каждой рамки, по убыванию
    :param threshold: доля перекрытия, выше которой рамки считаются одним лицом
    :return: индексы оставленных рамок
    """
    keep: list[int] = []
    areas = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
    for index in range(boxes.shape[0]):
        if not keep:
            keep.append(index)
            continue
        rest = np.asarray(keep, dtype=np.int64)
        xx1 = np.maximum(boxes[index, 0], boxes[rest, 0])
        yy1 = np.maximum(boxes[index, 1], boxes[rest, 1])
        xx2 = np.minimum(boxes[index, 2], boxes[rest, 2])
        yy2 = np.minimum(boxes[index, 3], boxes[rest, 3])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[index] + areas[rest] - inter
        overlap = np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
        if float(overlap.max()) <= threshold:
            keep.append(index)
    return keep


def postprocess(
    outputs: dict[str, np.ndarray],
    source_w: int,
    source_h: int,
    input_w: int,
    input_h: int,
    score_threshold: float,
    nms_threshold: float,
    top_k: int,
) -> list[tuple[int, int, int, int, float]]:
    """Разбирает выход сети в рамки кадра полного разрешения.

    :param outputs: выходы модели
    :param source_w: ширина исходного кадра
    :param source_h: высота исходного кадра
    :param input_w: ширина, на которую сжат кадр
    :param input_h: высота, на которую сжат кадр
    :param score_threshold: порог уверенности
    :param nms_threshold: порог перекрытия рамок
    :param top_k: сколько лучших рамок разбирать
    :return: список (x1, y1, x2, y2, уверенность)
    """
    cls_parts: list[np.ndarray] = []
    obj_parts: list[np.ndarray] = []
    box_parts: list[np.ndarray] = []
    for stride in STRIDES:
        cls_parts.append(outputs["cls_%d" % stride].reshape(-1))
        obj_parts.append(outputs["obj_%d" % stride].reshape(-1))
        box_parts.append(outputs["bbox_%d" % stride].reshape(-1, 4))
    cls = np.concatenate(cls_parts)
    obj = np.concatenate(obj_parts)
    boxes_raw = np.concatenate(box_parts)
    scores = cls * obj
    mask = np.where(scores > score_threshold)[0]
    if mask.size == 0:
        return []
    scores = scores[mask]
    boxes_raw = boxes_raw[mask]
    order = np.argsort(scores)[::-1][:top_k]
    scores = scores[order]
    boxes_raw = boxes_raw[order]

    # Рамка выводится в единицах шага; четыре — коэффициент самой модели.
    centre = boxes_raw * 4.0
    x1 = (centre[:, 0] - centre[:, 2] / 2.0) * source_w / input_w
    y1 = (centre[:, 1] - centre[:, 3] / 2.0) * source_h / input_h
    x2 = (centre[:, 0] + centre[:, 2] / 2.0) * source_w / input_w
    y2 = (centre[:, 1] + centre[:, 3] / 2.0) * source_h / input_h
    boxes = np.stack([x1, y1, x2, y2], axis=1)
    boxes[:, 0] = np.clip(boxes[:, 0], 0, source_w)
    boxes[:, 1] = np.clip(boxes[:, 1], 0, source_h)
    boxes[:, 2] = np.clip(boxes[:, 2], 0, source_w)
    boxes[:, 3] = np.clip(boxes[:, 3], 0, source_h)

    keep = non_maximum_suppression(boxes, scores, nms_threshold)
    faces: list[tuple[int, int, int, int, float]] = []
    for index in keep:
        left = int(round(boxes[index, 0]))
        top = int(round(boxes[index, 1]))
        right = int(round(boxes[index, 2]))
        bottom = int(round(boxes[index, 3]))
        # Пустая или вывернутая рамка — не результат, а ошибка разбора.
        if right <= left or bottom <= top:
            continue
        faces.append((left, top, right, bottom, float(scores[index])))
    return faces


class Detector:
    """Детектор лиц на одном изображении.

    :param model: путь к файлу модели
    :param provider: имя провайдера вычислений
    :param input_w: ширина входа сети
    :param input_h: высота входа сети
    :param score_threshold: порог уверенности
    :param nms_threshold: порог перекрытия рамок
    :param top_k: сколько лучших рамок разбирать
    """

    def __init__(
        self,
        model: str,
        provider: str,
        input_w: int,
        input_h: int,
        score_threshold: float,
        nms_threshold: float,
        top_k: int,
    ) -> None:
        available = ort.get_available_providers()
        wanted = provider if provider in available else None
        if wanted is None:
            fail(
                "Детектор: провайдер «%s» недоступен, есть: %s" % (provider, ", ".join(available))
            )
        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        try:
            self.session = ort.InferenceSession(
                model, sess_options=options, providers=[wanted]
            )
        except Exception as error:  # noqa: BLE001 — текст отказа должен быть полным
            fail("Детектор: модель не загрузилась «%s»: %s" % (model, error))
        self.input_name = self.session.get_inputs()[0].name
        shape = self.session.get_inputs()[0].shape
        # Фиксированный вход модели требует ровно своих размеров: молча
        # растягивать кадр под сеть нельзя, иначе рамки сместятся.
        self.input_w = input_w
        self.input_h = input_h
        if len(shape) == 4 and isinstance(shape[2], int) and isinstance(shape[3], int):
            if (shape[2], shape[3]) != (input_h, input_w):
                fail(
                    "Детектор: модель ждёт вход %dx%d, а задано %dx%d"
                    % (shape[3], shape[2], input_w, input_h)
                )
        self.provider = self.session.get_providers()[0]
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self.top_k = top_k

    def detect(self, frame: np.ndarray) -> tuple[list[tuple[int, int, int, int, float]], float]:
        """Ищет лица в кадре.

        :param frame: кадр в порядке BGR, HxWx3 или HxW
        :return: список рамок и время вывода в миллисекундах
        """
        started = time.perf_counter()
        gray = frame if frame.ndim == 2 else frame[:, :, :3]
        small = resize_area(gray, self.input_w, self.input_h)
        tensor = np.ascontiguousarray(small.transpose(2, 0, 1)[None, ...].astype(np.float32))
        outputs = self.session.run(None, {self.input_name: tensor})
        names = [out.name for out in self.session.get_outputs()]
        mapped = dict(zip(names, outputs))
        faces = postprocess(
            mapped,
            frame.shape[1],
            frame.shape[0],
            self.input_w,
            self.input_h,
            self.score_threshold,
            self.nms_threshold,
            self.top_k,
        )
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return faces, elapsed_ms

    def describe(self, model: str) -> str:
        """Описание себя для строки приветствия.

        :param model: путь к файлу модели
        :return: одна строка JSON
        """
        return json.dumps(
            {
                "status": "ready",
                "model": model,
                "provider": self.provider,
                "onnxruntime": ort.__version__,
                "numpy": np.__version__,
                "input": [self.input_w, self.input_h],
                "score_threshold": self.score_threshold,
                "nms_threshold": self.nms_threshold,
            },
            ensure_ascii=False,
        )


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Разбирает аргументы запуска.

    Аргументы приходят из конфигурации развёртывания, а не из данных
    задания: путь из данных — это путь из интернета в командную строку
    (ADR-0010, ограничение 2).

    :param argv: аргументы командной строки
    :return: разобранные аргументы
    """
    parser = argparse.ArgumentParser(description="Детектор лиц SYP")
    parser.add_argument("--model", required=True, help="путь к файлу модели ONNX")
    parser.add_argument("--provider", default="CUDAExecutionProvider", help="провайдер вычислений")
    parser.add_argument("--input-width", type=int, default=640, help="ширина входа сети")
    parser.add_argument("--input-height", type=int, default=640, help="высота входа сети")
    parser.add_argument("--score-threshold", type=float, default=0.9, help="порог уверенности")
    parser.add_argument("--nms-threshold", type=float, default=0.3, help="порог перекрытия рамок")
    parser.add_argument("--top-k", type=int, default=5000, help="сколько лучших рамок разбирать")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    """Точка входа программы детектора.

    :param argv: аргументы командной строки
    :return: код завершения: 0 при штатном закрытии потока кадров
    """
    args = parse_args(argv)
    detector = Detector(
        model=args.model,
        provider=args.provider,
        input_w=args.input_width,
        input_h=args.input_height,
        score_threshold=args.score_threshold,
        nms_threshold=args.nms_threshold,
        top_k=args.top_k,
    )
    out = sys.stdout.buffer
    out.write(detector.describe(args.model).encode("utf-8") + b"\n")
    out.flush()

    stdin = sys.stdin.buffer
    while True:
        head = stdin.read(HEADER.size)
        if not head:
            # Бэкенд закрыл поток: работа закончена штатно.
            return 0
        if len(head) != HEADER.size:
            fail("Детектор: заголовок кадра оборван на %d байтах" % len(head))
        number, width, height, pixel_format = HEADER.unpack(head)
        if pixel_format not in BYTES_PER_PIXEL:
            fail("Детектор: неизвестный формат кадра %d" % pixel_format)
        frame_bytes = width * height * BYTES_PER_PIXEL[pixel_format]
        data = read_exact(stdin, frame_bytes)
        shape = (height, width) if pixel_format == FORMAT_GRAY else (height, width, 3)
        frame = np.frombuffer(data, dtype=np.uint8).reshape(shape)
        faces, elapsed_ms = detector.detect(frame)
        answer = bytearray(ANSWER_HEADER.pack(number, elapsed_ms, len(faces)))
        for left, top, right, bottom, score in faces:
            answer += ANSWER_FACE.pack(left, top, right, bottom, score)
        out.write(bytes(answer))
        out.flush()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except BrokenPipeError:
        # Бэкенд прервал работу и закрыл поток кадров.
        raise SystemExit(0)
