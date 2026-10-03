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

# Виды моделей детектора, которые понимает программа. Вид приходит из
# конфигурации развёртывания вместе с путём к файлу: модель выбирается
# настройкой, а не компиляцией (ADR-0010, ограничение 2). Формат входа и
# разбор выхода у каждого вида свои — они и описаны ниже.
#
#   yunet     — YuNet из OpenCV Zoo, вход NCHW без нормализации, выходы
#               cls/obj/bbox/kps на шагах 8, 16 и 32.
#   scrfd     — SCRFD из InsightFace, вход NCHW RGB с нормализацией
#               (127,5 и 128), выходы score/bbox/kps на шагах 8…128.
#   retinaface— RetinaFace в весах DeepFace, вход NHWC RGB без нормализации,
#               выходы cls/bbox/landmark на шагах 32, 16 и 8.
MODEL_KINDS = ("yunet", "scrfd", "retinaface")

# Якоря RetinaFace: для каждого шага два якоря со смещениями. Значения взяты
# из исходного кода проекта, из которого сделан вес ONNX
# (`serengil/retinaface`, функция `detect_faces`): предобработка веса и наша
# обязаны совпадать, иначе рамки уезжают.
RETINAFACE_ANCHORS = {
    32: np.array([[-248.0, -248.0, 263.0, 263.0], [-120.0, -120.0, 135.0, 135.0]], dtype=np.float32),
    16: np.array([[-56.0, -56.0, 71.0, 71.0], [-24.0, -24.0, 39.0, 39.0]], dtype=np.float32),
    8: np.array([[-8.0, -8.0, 23.0, 23.0], [0.0, 0.0, 15.0, 15.0]], dtype=np.float32),
}

# Шаги сетки RetinaFace в порядке выхода модели: 32, затем 16, затем 8.
RETINAFACE_STRIDES = (32, 16, 8)

# Порядок строк RetinaFace: по каждому шагу сначала оценки классов, потом
# смещения рамок, потом ключевые точки.
RETINAFACE_ORDER = (32, 16, 8)

# Параметры изменения размера RetinaFace: минимальная сторона и потолок по
# большей стороне. Вес натренирован на таком масштабе, и отклонение от него
# меняет полноту, а не только скорость.
RETINAFACE_TARGET_MIN = 1024
RETINAFACE_MAX_SIDE = 1980

# Параметры SCRFD: среднее и стандартное отклонение нормализации входа.
SCRFD_INPUT_MEAN = 127.5
SCRFD_INPUT_STD = 128.0

# Шаги сетки SCRFD в порядке возрастания числа якорей. У веса 2.5G уровней
# пять, у веса 10G — три, и порядок выходов модели совпадает с этим списком.
SCRFD_STRIDE_BY_LEVEL = (8, 16, 32, 64, 128)


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
    """Уменьшает кадр до размера входа сети.

    Уменьшение идёт по площади, а не выборкой пикселя: при уменьшении втрое
    усреднение сохраняет мелкие лица, а выборка их теряет, и потерянное лицо
    не вернуть — второй проход по кадру не предусмотрен (ADR-0002). Там, где
    отношение сторон целое, площадь берётся точным усреднением блока; там, где
    не целое, строка или столбец переносится интерполяцией.

    Порядок вычислений выбран из-за скорости, и это не украшение: сначала
    уменьшается ширина, потом высота уже на уменьшенном массиве. Прежний
    вариант строил интегральную карту по всему кадру в двойной точности и на
    кадре 1920 на 1080 брал 155 миллисекунд — больше, чем вся остальная
    детекция вместе взятая: время уходило в подготовку кадра, а видеокарта
    простаивала. Усреднение блока сложением срезов даёт те же 18 миллисекунд,
    потому что numpy складывает соседние строки подряд, а усреднение по
    «шагу» внутри последнего измерения обходит память зигзагом.

    :param image: кадр HxWxC в порядке BGR
    :param width: ширина результата
    :param height: высота результата
    :return: кадр height x width x C
    """
    source_h, source_w = image.shape[0], image.shape[1]
    if source_h == height and source_w == width:
        return image
    values = image if image.ndim == 3 else image[:, :, None]
    if width != source_w:
        if source_w % width == 0:
            values = _block_mean(values, 1, source_w // width)
        else:
            values = _interpolate_axis(values, width, axis=1)
    if height != source_h:
        if source_h % height == 0:
            values = _block_mean(values, 0, source_h // height)
        else:
            values = _interpolate_axis(values, height, axis=0)
    return values[:, :, :3] if image.ndim == 3 else values[:, :, 0]


def _block_mean(values: np.ndarray, axis: int, block: int) -> np.ndarray:
    """Усредняет подряд идущие блоки значений вдоль одной оси.

    :param values: массив кадра
    :param axis: ось, по которой идут блоки
    :param block: сколько значений в блоке
    :return: массив с осью, укороченной в block раз
    """
    if block == 1:
        return values.astype(np.float32)
    total = np.asarray(values[:, 0::block] if axis == 1 else values[0::block, :], dtype=np.float32).copy()
    for index in range(1, block):
        piece = values[:, index::block] if axis == 1 else values[index::block, :]
        total += piece
    return total / float(block)


def _interpolate_axis(values: np.ndarray, size: int, axis: int) -> np.ndarray:
    """Линейно переносит содержимое вдоль одной оси массива.

    :param values: массив кадра
    :param size: сколько значений должно остаться на оси
    :param axis: ось, по которой идёт изменение
    :return: массив с укороченной осью
    """
    source = values.shape[axis]
    if source == size:
        return values.astype(np.float32)
    centers = (np.arange(size, dtype=np.float32) + 0.5) * source / size - 0.5
    centers = np.clip(centers, 0.0, source - 1.0)
    low = np.floor(centers).astype(np.int64)
    high = np.minimum(low + 1, source - 1)
    weight = (centers - low).astype(np.float32)
    shape = [1] * values.ndim
    shape[axis] = size
    left = np.take(values, low, axis=axis).astype(np.float32)
    right = np.take(values, high, axis=axis).astype(np.float32)
    return left * (1.0 - weight.reshape(shape)) + right * weight.reshape(shape)


def non_maximum_suppression(
    boxes: np.ndarray,
    scores: np.ndarray,
    threshold: float,
) -> list[int]:
    """Оставляет по одной рамке на лицо.

    Отбор идёт по убыванию уверенности: рамка отбрасывается, если она
    перекрывается с уже оставленной сильнее порога. Перекрытие считается
    векторно сразу со всеми оставленными: поштучный перебор в Python на кадре
    с тысячей кандидатов давал минуты вместо миллисекунд, а на низком пороге
    уверенности кандидатов именно тысячи.

    :param boxes: рамки в формате x1, y1, x2, y2
    :param scores: уверенность каждой рамки
    :param threshold: доля перекрытия, выше которой рамки считаются одним лицом
    :return: индексы оставленных рамок в порядке убывания уверенности
    """
    if boxes.shape[0] == 0:
        return []
    order = np.argsort(scores)[::-1]
    ordered = boxes[order]
    areas = (ordered[:, 2] - ordered[:, 0]) * (ordered[:, 3] - ordered[:, 1])
    keep: list[int] = []
    alive = np.ones(ordered.shape[0], dtype=bool)
    for index in range(ordered.shape[0]):
        if not alive[index]:
            continue
        keep.append(int(order[index]))
        rest = np.nonzero(alive[index + 1 :])[0] + index + 1
        if rest.size == 0:
            break
        xx1 = np.maximum(ordered[index, 0], ordered[rest, 0])
        yy1 = np.maximum(ordered[index, 1], ordered[rest, 1])
        xx2 = np.minimum(ordered[index, 2], ordered[rest, 2])
        yy2 = np.minimum(ordered[index, 3], ordered[rest, 3])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[index] + areas[rest] - inter
        overlap = np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
        alive[rest[overlap > threshold]] = False
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
    grid_parts: list[np.ndarray] = []
    stride_parts: list[np.ndarray] = []
    for stride in STRIDES:
        cls_parts.append(outputs["cls_%d" % stride].reshape(-1))
        obj_parts.append(outputs["obj_%d" % stride].reshape(-1))
        box_parts.append(outputs["bbox_%d" % stride].reshape(-1, 4))
        point_parts.append(outputs["kps_%d" % stride].reshape(-1, 10))
        # Сетка якорей модели: сначала строки, потом столбцы, шаг — stride.
        # Порядок совпадает с порядком, в котором сеть выдаёт оценки; сдвиг на
        # один якорь сдвигает все рамки, и ошибка выглядит правдоподобно.
        side = -(-input_w // stride)
        rows = np.repeat(np.arange(-(-input_h // stride)), side)
        cols = np.tile(np.arange(side), -(-input_h // stride))
        grid_parts.append(np.stack([rows, cols], axis=1).astype(np.float64))
        stride_parts.append(np.full(rows.shape, float(stride)))
    cls = np.concatenate(cls_parts)
    obj = np.concatenate(obj_parts)
    boxes_raw = np.concatenate(box_parts)
    points_raw = np.concatenate(point_parts)
    grid = np.concatenate(grid_parts)
    strides = np.concatenate(stride_parts)
    scores = cls * obj
    mask = np.where(scores > score_threshold)[0]
    if mask.size == 0:
        return []
    scores = scores[mask]
    boxes_raw = boxes_raw[mask]
    points_raw = points_raw[mask]
    grid = grid[mask]
    strides = strides[mask]
    order = np.argsort(scores)[::-1][:top_k]
    scores = scores[order]
    boxes_raw = boxes_raw[order]
    points_raw = points_raw[order]
    grid = grid[order]
    strides = strides[order]

    # Разбор рамки. Сеть отдаёт четыре расстояния от якоря в единицах шага:
    # слева, сверху, справа, снизу. Ширина — сумма расстояний, а не третье
    # число само по себе; спутать эти два прочтения — значит получить рамки в
    # углу кадра вместо лиц.
    left, top, right, bottom = (boxes_raw[:, i] for i in range(4))
    centre_x = (grid[:, 1] + left) * strides
    centre_y = (grid[:, 0] + top) * strides
    width = (left + right) * strides
    height = (top + bottom) * strides
    boxes = np.stack(
        [
            (centre_x - width / 2.0) * source_w / input_w,
            (centre_y - height / 2.0) * source_h / input_h,
            (centre_x + width / 2.0) * source_w / input_w,
            (centre_y + height / 2.0) * source_h / input_h,
        ],
        axis=1,
    )
    boxes[:, 0] = np.clip(boxes[:, 0], 0, source_w)
    boxes[:, 1] = np.clip(boxes[:, 1], 0, source_h)
    boxes[:, 2] = np.clip(boxes[:, 2], 0, source_w)
    boxes[:, 3] = np.clip(boxes[:, 3], 0, source_h)

    # Точки. Сеть отдаёт пять пар так же, как и четыре расстояния рамки: от
    # якоря. Порядок — левый глаз, правый глаз, нос, левый угол рта, правый.
    # Считать их иначе нельзя: сдвиг на полшага якоря даёт точки, сдвинутые на
    # половину шага сетки, и выравнивание лица становится хуже, чем его
    # отсутствие.
    points = np.empty((points_raw.shape[0], 10), dtype=np.float64)
    for i in range(5):
        points[:, i * 2] = (grid[:, 1] + points_raw[:, i * 2]) * strides
        points[:, i * 2 + 1] = (grid[:, 0] + points_raw[:, i * 2 + 1]) * strides
    points[:, 0::2] *= source_w / input_w
    points[:, 1::2] *= source_h / input_h

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
        faces.append(
            (
                left,
                top,
                right,
                bottom,
                float(scores[index]),
                *[int(round(v)) for v in points[index]],
            )
        )
    return faces


def resize_bilinear(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Уменьшает или увеличивает кадр билинейной интерполяцией.

    Билинейная интерполяция — не вкусовое решение: ею пользуются исходные
    реализации SCRFD и RetinaFace, на которых натренированы их веса. Своя
    интерполяция сдвинула бы рамки по сравнению с той, на которой модель
    обучалась, и сравнение моделей стало бы сравнением не моделей, а
    преобразований кадра.

    Оси переносятся по очереди, а не одним выражением: выбор строки и
    столбца сразу по двум осям заставляет numpy обходить память зигзагом, и
    на кадре 1920 на 1080 это 190 миллисекунд вместо сорока.

    :param image: кадр HxWxC в порядке BGR
    :param width: ширина результата
    :param height: высота результата
    :return: кадр height x width x C
    """
    source_h, source_w = image.shape[0], image.shape[1]
    if source_h == height and source_w == width:
        return image
    values = image if image.ndim == 3 else image[:, :, None]
    if width != source_w:
        values = _interpolate_axis(values, width, axis=1)
    if height != source_h:
        values = _interpolate_axis(values, height, axis=0)
    return values[:, :, :3].astype(np.uint8) if image.ndim == 3 else values[:, :, 0].astype(np.uint8)


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
    point_parts: list[np.ndarray] = []
    box_parts: list[np.ndarray] = []
    grid_parts: list[np.ndarray] = []
    stride_parts: list[np.ndarray] = []
    for stride in STRIDES:
        cls_parts.append(outputs["cls_%d" % stride].reshape(-1))
        obj_parts.append(outputs["obj_%d" % stride].reshape(-1))
        box_parts.append(outputs["bbox_%d" % stride].reshape(-1, 4))
        point_parts.append(outputs["kps_%d" % stride].reshape(-1, 10))
        # Сетка якорей модели: сначала строки, потом столбцы, шаг — stride.
        # Порядок совпадает с порядком, в котором сеть выдаёт оценки; сдвиг на
        # один якорь сдвигает все рамки, и ошибка выглядит правдоподобно.
        side = -(-input_w // stride)
        rows = np.repeat(np.arange(-(-input_h // stride)), side)
        cols = np.tile(np.arange(side), -(-input_h // stride))
        grid_parts.append(np.stack([rows, cols], axis=1).astype(np.float64))
        stride_parts.append(np.full(rows.shape, float(stride)))
    cls = np.concatenate(cls_parts)
    obj = np.concatenate(obj_parts)
    boxes_raw = np.concatenate(box_parts)
    points_raw = np.concatenate(point_parts)
    grid = np.concatenate(grid_parts)
    strides = np.concatenate(stride_parts)
    scores = cls * obj
    mask = np.where(scores > score_threshold)[0]
    if mask.size == 0:
        return []
    scores = scores[mask]
    boxes_raw = boxes_raw[mask]
    points_raw = points_raw[mask]
    grid = grid[mask]
    strides = strides[mask]
    order = np.argsort(scores)[::-1][:top_k]
    scores = scores[order]
    boxes_raw = boxes_raw[order]
    points_raw = points_raw[order]
    grid = grid[order]
    strides = strides[order]

    # Разбор рамки. Сеть отдаёт четыре расстояния от якоря в единицах шага:
    # слева, сверху, справа, снизу. Ширина — сумма расстояний, а не третье
    # число само по себе; спутать эти два прочтения — значит получить рамки в
    # углу кадра вместо лиц.
    left, top, right, bottom = (boxes_raw[:, i] for i in range(4))
    centre_x = (grid[:, 1] + left) * strides
    centre_y = (grid[:, 0] + top) * strides
    width = (left + right) * strides
    height = (top + bottom) * strides
    boxes = np.stack(
        [
            (centre_x - width / 2.0) * source_w / input_w,
            (centre_y - height / 2.0) * source_h / input_h,
            (centre_x + width / 2.0) * source_w / input_w,
            (centre_y + height / 2.0) * source_h / input_h,
        ],
        axis=1,
    )
    boxes[:, 0] = np.clip(boxes[:, 0], 0, source_w)
    boxes[:, 1] = np.clip(boxes[:, 1], 0, source_h)
    boxes[:, 2] = np.clip(boxes[:, 2], 0, source_w)
    boxes[:, 3] = np.clip(boxes[:, 3], 0, source_h)

    # Точки: пять пар так же, как четыре расстояния рамки, от того же якоря.
    # Порядок — левый глаз, правый глаз, нос, левый угол рта, правый.
    points = np.empty((points_raw.shape[0], 10), dtype=np.float64)
    for i in range(5):
        points[:, i * 2] = (grid[:, 1] + points_raw[:, i * 2]) * strides
        points[:, i * 2 + 1] = (grid[:, 0] + points_raw[:, i * 2 + 1]) * strides
    points[:, 0::2] *= source_w / input_w
    points[:, 1::2] *= source_h / input_h

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
        faces.append(
            (
                left,
                top,
                right,
                bottom,
                float(scores[index]),
                *[int(round(v)) for v in points[index]],
            ),
        )
    return faces


def resize_bilinear(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Уменьшает или увеличивает кадр билинейной интерполяцией.

    Билинейная интерполяция — не вкусовое решение: ею пользуются исходные
    реализации SCRFD и RetinaFace, на которых натренированы их веса. Своя
    интерполяция сдвинула бы рамки по сравнению с той, на которой модель
    обучалась, и сравнение моделей стало бы сравнением не моделей, а
    преобразований кадра.

    :param image: кадр HxWxC в порядке BGR
    :param width: ширина результата
    :param height: высота результата
    :return: кадр height x width x C
    """
    source_h, source_w = image.shape[0], image.shape[1]
    if source_h == height and source_w == width:
        return image
    values = image if image.ndim == 3 else image[:, :, None]
    # Координаты центров исходных пикселей, попадающих в каждый новый.
    ys = (np.arange(height, dtype=np.float32) + 0.5) * source_h / height - 0.5
    xs = (np.arange(width, dtype=np.float32) + 0.5) * source_w / width - 0.5
    ys = np.clip(ys, 0.0, source_h - 1.0)
    xs = np.clip(xs, 0.0, source_w - 1.0)
    y0 = np.floor(ys).astype(np.int64)
    x0 = np.floor(xs).astype(np.int64)
    y1 = np.minimum(y0 + 1, source_h - 1)
    x1 = np.minimum(x0 + 1, source_w - 1)
    wy = (ys - y0)[:, None, None].astype(np.float32)
    wx = (xs - x0)[None, :, None].astype(np.float32)
    values = values.astype(np.float32)
    top = values[y0][:, x0] * (1.0 - wx) + values[y0][:, x1] * wx
    bottom = values[y1][:, x0] * (1.0 - wx) + values[y1][:, x1] * wx
    result = top * (1.0 - wy) + bottom * wy
    return result.astype(np.uint8) if image.ndim == 3 else result[:, :, 0].astype(np.uint8)


def _looks_like_kps(names: list[str], outputs: dict[str, np.ndarray]) -> bool:
    """Проверяет, есть ли у модели выходы ключевых точек.

    У SCRFD выходы идут блоками по уровням сетки: сначала оценки, потом
    расстояния до рамок, потом ключевые точки. Наличие третьего блока
    определяется числом выходов, а их делимостью на три — наличие точек.

    :param names: имена выходов в порядке модели
    :param outputs: сами выходы
    :return: есть ли блок ключевых точек
    """
    if len(names) % 3 != 0:
        return False
    first = outputs[names[0]]
    return int(np.asarray(first).shape[-1]) == 1


def postprocess_scrfd(
    outputs: dict[str, np.ndarray],
    source_w: int,
    source_h: int,
    input_w: int,
    input_h: int,
    score_threshold: float,
    nms_threshold: float,
    top_k: int,
) -> list[tuple[int, int, int, int, float]]:
    """Разбирает выход SCRFD в рамки кадра полного разрешения.

    Схема разбора взята из исходного кода InsightFace
    (`python-package/insightface/model_zoo/scrfd.py`, ветка `master` на дату
    замера): рамка восстанавливается из расстояний от центра якоря, центр
    якоря — это узел сетки, умноженный на шаг. Расстояния уже умножаются на
    шаг самой сетью; экспоненты в этой версии кода нет, и добавлять её
    значило бы считать не то, что считает эталонная реализация.

    :param outputs: выходы модели по именам
    :param source_w: ширина исходного кадра
    :param source_h: высота исходного кадра
    :param input_w: ширина входа сети
    :param input_h: высота входа сети
    :param score_threshold: порог уверенности
    :param nms_threshold: порог перекрытия рамок
    :param top_k: сколько лучших рамок разбирать
    :return: список (x1, y1, x2, y2, уверенность)
    """
    names = list(outputs.keys())
    # Состав выходов выводится из самого файла модели, а не из её названия:
    # у SCRFD 2.5G пять уровней сетки и один якорь на узел, у SCRFD 10G три
    # уровня и два якоря. Ошибиться здесь — значит приклеить рамку к чужому
    # якорю и получить правдоподобную, но неверную картину.
    levels = len(names) // 3 if names and _looks_like_kps(names, outputs) else len(names) // 2
    score_parts = []
    box_parts = []
    for index in range(levels):
        stride = SCRFD_STRIDE_BY_LEVEL[len(score_parts)]
        # Расстояния сети приходят в единицах шага: исходная реализация
        # умножает их на шаг перед восстановлением рамки, и это умножение —
        # часть модели, а не наше решение.
        score_parts.append(outputs[names[index]].reshape(-1))
        box_parts.append(outputs[names[index + levels]].reshape(-1, 4) * stride)
    scores_all = np.concatenate(score_parts)
    boxes_all = np.concatenate(box_parts)
    scores_mask = scores_all > score_threshold
    if not scores_mask.any():
        return []
    scores = scores_all[scores_mask][:top_k]
    distances = boxes_all[scores_mask][:top_k]

    # Центры якорей восстанавливаются из сетки: для шага s узлов (h/s) на
    # (w/s), порядок — сначала строки, потом столбцы. Порядок обязан совпасть
    # с порядком выходов сети, иначе рамка приклеится к чужому якорю.
    centers = []
    for index, stride in enumerate(SCRFD_STRIDE_BY_LEVEL[:levels]):
        grid_h = -(-input_h // stride)
        grid_w = -(-input_w // stride)
        per_cell = int(round(score_parts[index].shape[0] / float(grid_h * grid_w)))
        rows = np.repeat(np.arange(grid_h, dtype=np.float32), grid_w) * stride
        cols = np.tile(np.arange(grid_w, dtype=np.float32), grid_h) * stride
        cells = np.stack([cols, rows], axis=1)
        centers.append(np.repeat(cells, per_cell, axis=0))
    centers_all = np.concatenate(centers, axis=0)
    if centers_all.shape[0] != scores_mask.shape[0]:
        fail(
            "Детектор: сетка якорей SCRFD не совпала с выходом модели: "
            "%d якорей против %d оценок" % (centers_all.shape[0], scores_mask.shape[0])
        )
    centers = centers_all[scores_mask][:top_k]

    order = np.argsort(scores)[::-1]
    scores = scores[order]
    distances = distances[order]
    centers = centers[order]

    left = centers[:, 0] - distances[:, 0]
    top = centers[:, 1] - distances[:, 1]
    right = centers[:, 0] + distances[:, 2]
    bottom = centers[:, 1] + distances[:, 3]
    # Координаты получены в масштабе кадра, поданного сети: пересчёт идёт
    # обратным масштабом изменения размера.
    boxes = np.stack(
        [
            left * source_w / input_w,
            top * source_h / input_h,
            right * source_w / input_w,
            bottom * source_h / input_h,
        ],
        axis=1,
    )
    boxes[:, 0] = np.clip(boxes[:, 0], 0, source_w)
    boxes[:, 1] = np.clip(boxes[:, 1], 0, source_h)
    boxes[:, 2] = np.clip(boxes[:, 2], 0, source_w)
    boxes[:, 3] = np.clip(boxes[:, 3], 0, source_h)
    keep = non_maximum_suppression(boxes, scores, nms_threshold)
    faces: list[tuple[int, int, int, int, float]] = []
    for index in keep:
        x1 = int(round(boxes[index, 0]))
        y1 = int(round(boxes[index, 1]))
        x2 = int(round(boxes[index, 2]))
        y2 = int(round(boxes[index, 3]))
        if x2 <= x1 or y2 <= y1:
            continue
        faces.append((x1, y1, x2, y2, float(scores[index])))
    return faces


def postprocess_retinaface(
    outputs: dict[str, np.ndarray],
    source_w: int,
    source_h: int,
    input_w: int,
    input_h: int,
    score_threshold: float,
    nms_threshold: float,
    top_k: int,
) -> list[tuple[int, int, int, int, float]]:
    """Разбирает выход RetinaFace в рамки кадра полного разрешения.

    Схема разбора взята из исходного кода `serengil/retinaface`
    (`RetinaFace.py`, функция `detect_faces`, и `commons/postprocess.py`):
    якоря строятся на каждом шаге, рамка — якорь, сдвинутый предсказанными
    дельтами, а вероятность лица берётся из второго класса каждого из двух
    якорей. Это та же арифметика, что и в эталонной реализации, иначе вес
    и наша программа говорили бы о разных вещах.

    :param outputs: выходы модели по именам
    :param source_w: ширина исходного кадра
    :param source_h: высота исходного кадра
    :param input_w: ширина кадра после изменения размера
    :param input_h: высота кадра после изменения размера
    :param score_threshold: порог уверенности
    :param nms_threshold: порог перекрытия рамок
    :param top_k: сколько лучших рамок разбирать
    :return: список (x1, y1, x2, y2, уверенность)
    """
    boxes_parts: list[np.ndarray] = []
    scores_parts: list[np.ndarray] = []
    for stride in RETINAFACE_ORDER:
        classes = outputs["cls_prob_stride%d" % stride]
        deltas = outputs["bbox_pred_stride%d" % stride]
        height, width = deltas.shape[1], deltas.shape[2]
        count = height * width
        # Два якоря на узел: у каждого свой фон и своё лицо, поэтому оценки
        # классов читаются со второй половины канала.
        scores = classes[:, :, :, 2:].reshape(count * 2, 1)
        deltas = deltas.reshape(count * 2, 4)
        anchors = retinaface_anchors(height, width, stride)
        boxes_parts.append(retinaface_bbox(anchors, deltas))
        scores_parts.append(scores)
    boxes = np.concatenate(boxes_parts, axis=0)
    scores = np.concatenate(scores_parts, axis=0)
    mask = (scores > score_threshold).reshape(-1)
    if not mask.any():
        return []
    boxes = boxes[mask][:top_k]
    scores = scores[mask][:top_k].reshape(-1)
    order = np.argsort(scores)[::-1]
    boxes = boxes[order]
    scores = scores[order]
    # Координаты сейчас в масштабе изменённого кадра: обратный масштаб и
    # обрезка по границам исходного кадра.
    boxes[:, 0::2] = np.clip(boxes[:, 0::2] * source_w / input_w, 0, source_w)
    boxes[:, 1::2] = np.clip(boxes[:, 1::2] * source_h / input_h, 0, source_h)
    keep = non_maximum_suppression(boxes, scores, nms_threshold)
    faces: list[tuple[int, int, int, int, float]] = []
    for index in keep:
        x1 = int(round(boxes[index, 0]))
        y1 = int(round(boxes[index, 1]))
        x2 = int(round(boxes[index, 2]))
        y2 = int(round(boxes[index, 3]))
        if x2 <= x1 or y2 <= y1:
            continue
        faces.append((x1, y1, x2, y2, float(scores[index])))
    return faces


def retinaface_anchors(
    height: int,
    width: int,
    stride: int,
) -> np.ndarray:
    """Строит якоря RetinaFace на сетке одного шага.

    :param height: высота сетки
    :param width: ширина сетки
    :param stride: шаг сетки
    :return: массив (height * width * 2, 4) с координатами якорей
    """
    base = RETINAFACE_ANCHORS[stride]
    xs = np.tile(np.arange(width, dtype=np.float32), (height, 1)) * stride
    ys = np.repeat(np.arange(height, dtype=np.float32)[:, None], width, axis=1) * stride
    anchors = np.empty((height, width, base.shape[0], 4), dtype=np.float32)
    for index in range(base.shape[0]):
        anchors[:, :, index, 0] = xs + base[index, 0]
        anchors[:, :, index, 1] = ys + base[index, 1]
        anchors[:, :, index, 2] = xs + base[index, 2]
        anchors[:, :, index, 3] = ys + base[index, 3]
    return anchors.reshape(-1, 4)


def retinaface_bbox(
    anchors: np.ndarray,
    deltas: np.ndarray,
) -> np.ndarray:
    """Собирает рамку из якоря и предсказанных дельт.

    Формула совпадает с `commons/postprocess.py` проекта `serengil/retinaface`:
    ширина и центр якоря масштабируются предсказанием, а сама рамка
    восстанавливается от нового центра.

    :param anchors: якоря (N, 4)
    :param deltas: предсказания (N, 4)
    :return: рамки (N, 4)
    """
    widths = anchors[:, 2] - anchors[:, 0] + 1.0
    heights = anchors[:, 3] - anchors[:, 1] + 1.0
    centre_x = anchors[:, 0] + 0.5 * (widths - 1.0)
    centre_y = anchors[:, 1] + 0.5 * (heights - 1.0)
    pred_x = deltas[:, 0] * widths + centre_x
    pred_y = deltas[:, 1] * heights + centre_y
    pred_w = np.exp(deltas[:, 2]) * widths
    pred_h = np.exp(deltas[:, 3]) * heights
    return np.stack(
        [
            pred_x - 0.5 * (pred_w - 1.0),
            pred_y - 0.5 * (pred_h - 1.0),
            pred_x + 0.5 * (pred_w - 1.0),
            pred_y + 0.5 * (pred_h - 1.0),
        ],
        axis=1,
    )


def prepare_yunet(
    frame: np.ndarray,
    input_w: int,
    input_h: int,
) -> np.ndarray:
    """Готовит кадр к сети YuNet.

    YuNet принимает кадр без нормализации, в порядке BGR, приведённый к
    квадрату размера входа сети.

    :param frame: кадр HxWx3 в порядке BGR
    :param input_w: ширина входа
    :param input_h: высота входа
    :return: натяжка NCHW float32
    """
    small = resize_area(frame, input_w, input_h)
    return np.ascontiguousarray(small.transpose(2, 0, 1)[None, ...].astype(np.float32))


def prepare_scrfd(
    frame: np.ndarray,
    input_w: int,
    input_h: int,
) -> tuple[np.ndarray, int, int]:
    """Готовит кадр к сети SCRFD.

    Порядок действий взят из `insightface/model_zoo/scrfd.py`: пропорции
    сохраняются, недостающие справа и снизу места заполняются нулём, затем
    кадр переводится в RGB и приводится к диапазону примерно от -1 до 1.

    :param frame: кадр HxWx3 в порядке BGR
    :param input_w: ширина входа
    :param input_h: высота входа
    :return: натяжка NCHW float32 и фактические ширина и высота кадра сети
    """
    source_h, source_w = frame.shape[0], frame.shape[1]
    image_ratio = float(source_h) / float(source_w)
    model_ratio = float(input_h) / float(input_w)
    if image_ratio > model_ratio:
        new_h = input_h
        new_w = int(new_h / image_ratio)
    else:
        new_w = input_w
        new_h = int(new_w * image_ratio)
    resized = resize_bilinear(frame, max(new_w, 1), max(new_h, 1))
    padded = np.zeros((input_h, input_w, 3), dtype=np.uint8)
    padded[: max(new_h, 1), : max(new_w, 1), :] = resized
    values = (padded.astype(np.float32) - SCRFD_INPUT_MEAN) / SCRFD_INPUT_STD
    tensor = np.ascontiguousarray(values.transpose(2, 0, 1)[None, ..., ::-1])
    return tensor, input_w, input_h


def prepare_retinaface(frame: np.ndarray) -> tuple[np.ndarray, int, int]:
    """Готовит кадр к сети RetinaFace.

    Порядок действий взят из `serengil/retinaface/commons/preprocess.py`:
    кадр приводится к масштабу, на котором натренирован вес (1024 по
    меньшей стороне, не больше 1980 по большей), переводится в RGB и
    кладётся в натяжку NHWC без нормализации.

    :param frame: кадр HxWx3 в порядке BGR
    :return: натяжка NHWC float32 и ширина с высотой кадра после изменения размера
    """
    source_h, source_w = frame.shape[0], frame.shape[1]
    if source_w > source_h:
        short_side, long_side = source_h, source_w
    else:
        short_side, long_side = source_w, source_h
    scale = float(RETINAFACE_TARGET_MIN) / float(short_side)
    if round(scale * long_side) > RETINAFACE_MAX_SIDE:
        scale = float(RETINAFACE_MAX_SIDE) / float(long_side)
    new_w = max(int(round(source_w * scale)), 1)
    new_h = max(int(round(source_h * scale)), 1)
    resized = resize_bilinear(frame, new_w, new_h).astype(np.float32)
    tensor = np.ascontiguousarray(resized[None, :, :, ::-1])
    return tensor, new_w, new_h


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
        model_kind: str,
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
        if provider != "CPUExecutionProvider" and hasattr(ort, "preload_dlls"):
            # Библиотеки CUDA лежат в колесах NVIDIA, а не в системных путях
            # поиска. Без этой загрузки провайдер не поднимается, а onnxruntime
            # молча уходит на CPU: список доступных провайдеров CUDAExecutionProvider
            # содержит независимо от того, загрузилась ли она. Смысл проверки
            # ниже — в том, что провайдеры сессии начинаются с CUDA, а не с CPU.
            ort.preload_dlls(cuda=True, cudnn=True)
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
        if self.provider != wanted:
            # Молчаливый уход на CPU — самая опасная поломка детектора: она
            # выглядит как «просто медленно», а на 88 643 кадрах это часы.
            # Поэтому замена провайдера объявлена отказом, а не подменой.
            fail(
                "Детектор: провайдер «%s» не поднялся, вычисления пошли на «%s». "
                "Проверьте, что образ содержит библиотеки CUDA" % (provider, self.provider)
            )
        self.score_threshold = score_threshold
        self.nms_threshold = nms_threshold
        self.top_k = top_k
        self.model_kind = model_kind
        self.input_name_shape = shape

    def detect(self, frame: np.ndarray) -> tuple[list[tuple[int, int, int, int, float]], float]:
        """Ищет лица в кадре.

        :param frame: кадр в порядке BGR, HxWx3 или HxW
        :return: список рамок и время вывода в миллисекундах
        """
        started = time.perf_counter()
        gray = frame if frame.ndim == 2 else frame[:, :, :3]
        if self.model_kind == "yunet":
            tensor = prepare_yunet(gray, self.input_w, self.input_h)
            net_w, net_h = self.input_w, self.input_h
            outputs = self.session.run(None, {self.input_name: tensor})
            mapped = {out.name: value for out, value in zip(self.session.get_outputs(), outputs)}
            faces = postprocess(
                mapped,
                frame.shape[1],
                frame.shape[0],
                net_w,
                net_h,
                self.score_threshold,
                self.nms_threshold,
                self.top_k,
            )
        elif self.model_kind == "scrfd":
            tensor, net_w, net_h = prepare_scrfd(gray, self.input_w, self.input_h)
            outputs = self.session.run(None, {self.input_name: tensor})
            mapped = {out.name: value for out, value in zip(self.session.get_outputs(), outputs)}
            faces = postprocess_scrfd(
                mapped,
                frame.shape[1],
                frame.shape[0],
                net_w,
                net_h,
                self.score_threshold,
                self.nms_threshold,
                self.top_k,
            )
        else:
            tensor, net_w, net_h = prepare_retinaface(gray)
            outputs = self.session.run(None, {self.input_name: tensor})
            mapped = {out.name: value for out, value in zip(self.session.get_outputs(), outputs)}
            faces = postprocess_retinaface(
                mapped,
                frame.shape[1],
                frame.shape[0],
                net_w,
                net_h,
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
                "model_kind": self.model_kind,
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
    parser.add_argument(
        "--model-kind",
        default="yunet",
        choices=MODEL_KINDS,
        help="вид модели: как готовится кадр и как разбирается выход",
    )
    parser.add_argument("--provider", default="CUDAExecutionProvider", help="провайдер вычислений")
    parser.add_argument("--input-width", type=int, default=640, help="ширина входа сети")
    parser.add_argument("--input-height", type=int, default=640, help="высота входа сети")
    parser.add_argument("--score-threshold", type=float, default=0.6, help="порог уверенности")
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
        model_kind=args.model_kind,
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
