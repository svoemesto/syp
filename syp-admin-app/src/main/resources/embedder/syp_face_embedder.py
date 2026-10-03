#!/usr/bin/env python3
# SYP — эмбеддер лиц: превращает найденные лица в векторы признаков.
#
# Назначение. Кластеры лиц строятся по векторам, и без них группировки нет: сам
# детектор умеет только находить рамки. Вектор здесь — , а то, на чём потом
# обучается наш собственный классификатор: оператор даёт имена, и по этим именам
# модель учится. Свойство лица извлекает готовая открытая модель SFace
# (OpenCV Zoo, Apache 2.0), решает классификатор наш.
#
# Почему пять точек обязательны. SFace и всякое такое обучение ждут лицо,
# выровненное по пяти точкам: глаза, нос, уголки рта. Без выравнивания модель
# получает рамку, растянутую как попало, и выдаёт вектор, годный ни к чему:
# замер показал, что такие расстояния не отличают разных людей от разных людей.
# Точки приходят от детектора, который их отдаёт.
#
# Протокол. На вход — кадр и список лиц (рамка и пять точек на каждое), на выход —
# по вектору на лицо. Всё в том же духе, что у детектора: программа молчит, пока
# не попросят, и падает с текстом, а не молча.
"""SYP — эмбеддер лиц."""

from __future__ import annotations

import argparse
import json
import struct
import sys

import numpy as np
import onnxruntime as ort

# Куда модель выровненных лиц. SFace ждёт ровно этот размер.
ALIGNED = 112
# Точек на лицо: пять пар координат.
POINTS = 5
# Сколько каналов в кадре от декодера.
CHANNELS = 3
# Длина вектора SFace.
DIMENSION = 128

# Куда должны попасть точки выровненного лица. Взят из примера SFace, а не
# придуман: модель училась на этих именно координатах, и «примерно по центру»
# даёт вектор заметно худший.
TEMPLATE = np.array(
    [
        [38.2946, 51.6963],
        [73.5318, 51.5014],
        [56.0252, 71.7366],
        [41.5493, 92.3655],
        [70.7299, 92.2041],
    ],
    dtype=np.float32,
)

# Запрос: номер кадра, ширина, высота, число лиц, затем на каждое лицо рамка и
# пять точек — пятнадцать коротких целых. Порядок и размер совпадают с ответом
# детектора, и меняются только вместе с ним.
REQUEST = struct.Struct("<iHH")
# Число лиц идёт после кадра: его нельзя положить в заголовок, потому что
# размер кадра известен только после того, как прочитан сам кадр.
COUNT = struct.Struct("<H")
REQUEST_FACE = struct.Struct("<hhhhf" + "hh" * 5)
# Ответ: номер кадра, число векторов, затем векторы по 128 float.
ANSWER = struct.Struct("<iH")
ANSWER_VECTOR = struct.Struct("<" + "f" * DIMENSION)

# Поток кадров начинается с этого заголовка, как у детектора.
STREAM_HEADER = struct.Struct("<IIII")


def fail(message: str) -> None:
    """Останавливает программу с понятным текстом.

    :param message: что не так
    """
    raise SystemExit(f"SYP: эмбеддер лиц: {message}")


def similarity_transform(points: np.ndarray) -> np.ndarray:
    """Находит преобразование из точек лица в точки шаблона.

    :param points: пять точек лица в порядке шаблона
    :return: матрица 2 на 3
    """
    if points.shape != (POINTS, 2):
        fail(f"ожидалось {POINTS} точек, пришло {points.shape[0]}")
    matrix, _ = cv2_estimate_affine(points, TEMPLATE)
    return matrix


def cv2_estimate_affine(source: np.ndarray, target: np.ndarray) -> tuple[np.ndarray, float]:
    """Оценивает подобие по трём парам точек.

    Считается вручную, без OpenCV: контейнер его не содержит, а три пары точек
    разбираются за десять строк. Метод Делоне для трёх точек — это просто
    треугольник, и для него достаточно перестановки определителя.

    :param source: три пары точек источника
    :param target: три пары точек цели
    :return: матрица и средняя ошибка в пикселях
    """
    if source.shape != (3, 2) or target.shape != (3, 2):
        fail("для подобия нужны ровно три пары точек")
    a = np.column_stack((source, np.ones(3)))
    try:
        solution, *_ = np.linalg.lstsq(a, target, rcond=None)
    except np.linalg.LinAlgError:
        fail("точки лица вырождены: не хватает формы для выравнивания")
    # solution имеет вид (3, 2): строки — веса трёх точек, столбцы — координаты.
    # Превращается в привычную матрицу подобия 2 на 3 перестановкой строк.
    matrix = solution.T
    error = float(np.abs(a @ solution - target).max())
    return matrix, error


def align_face(gray: np.ndarray, face: np.ndarray) -> np.ndarray:
    """Выравнивает лицо по пяти точкам в квадрат модели.

    :param gray: кадр в оттенках серого
    :param face: пятнадцать чисел: рамка и пять точек
    :return: изображение ALIGNED на ALIGNED
    """
    points = np.array(
        [[face[5 + i * 2], face[6 + i * 2]] for i in range(POINTS)],
        dtype=np.float32,
    )
    matrix, _ = cv2_estimate_affine(points[:3], TEMPLATE[:3])
    # Обратное преобразование: модель ждёт картинку, а матрица переводит лицо в
    # шаблон, поэтому для выборки пикселей нужен обратный переход.
    full = np.empty((3, 3), dtype=np.float64)
    full[0:2] = matrix
    full[2] = (0.0, 0.0, 1.0)
    try:
        inverse = np.linalg.inv(full)
    except np.linalg.LinAlgError:
        fail("точки лица вырождены: переход не обращается")
    ys, xs = np.mgrid[0:ALIGNED, 0:ALIGNED]
    ones = np.ones_like(xs, dtype=np.float64)
    homogeneous = np.stack((xs, ys, ones), axis=-1)
    mapped = homogeneous @ inverse.T
    src_x = np.round(mapped[..., 0]).astype(int)
    src_y = np.round(mapped[..., 1]).astype(int)
    inside = (src_x >= 0) & (src_x < gray.shape[1]) & (src_y >= 0) & (src_y < gray.shape[0])
    out = np.full((ALIGNED, ALIGNED), 127, dtype=np.float32)
    out[inside] = gray[src_y[inside], src_x[inside]]
    return out


class Embedder:
    """Считает вектор лица моделью SFace.

    :param model: путь к файлу модели
    :param provider: провайдер onnxruntime
    """

    def __init__(self, model: str, provider: str) -> None:
        try:
            self.session = ort.InferenceSession(model, providers=[provider])
        except Exception as error:  # noqa: BLE001 — причина попадёт в текст
            fail(f"модель не загрузилась: {error}")
        self.input = self.session.get_inputs()[0]
        if list(self.input.shape) != [1, 3, ALIGNED, ALIGNED]:
            fail(f"модель ждёт вход {self.input.shape}, а подаётся 1x3x{ALIGNED}x{ALIGNED}")
        self.output = self.session.get_outputs()[0]
        self.provider = self.session.get_providers()[0]
        if self.provider != provider:
            fail(
                f"провайдер «{provider}» не поднялся, работает «{self.provider}»: "
                f"молчаливый уход на другое устройство — самая опасная ошибка"
            )

    def vector(self, gray: np.ndarray, face: np.ndarray) -> np.ndarray:
        """Считает нормированный вектор одного лица.

        :param gray: кадр в оттенках серого
        :param face: рамка и пять точек
        :return: вектор DIMENSION чисел, длина единичная
        """
        aligned = align_face(gray, face)
        tensor = np.repeat(aligned[None, :, :], 3, axis=0)[None, :, :].astype(np.float32)
        raw = np.asarray(self.session.run([self.output.name], {self.input.name: tensor})[0]).ravel()
        if raw.size != DIMENSION:
            fail(f"модель вернула {raw.size} чисел вместо {DIMENSION}")
        norm = float(np.linalg.norm(raw))
        if norm < 1e-9:
            fail("модель вернула нулевой вектор: на этом лице вектора нет")
        return raw / norm

    def greeting(self) -> str:
        """Описывает себя для строки приветствия.

        :return: одна строка JSON
        """
        return json.dumps(
            {
                "status": "ready",
                "model": self.input.name,
                "dimension": DIMENSION,
                "aligned": ALIGNED,
                "onnxruntime": ort.__version__,
                "numpy": np.__version__,
                "provider": self.provider,
            },
            ensure_ascii=False,
        )


def read_exactly(stream, size: int) -> bytes:
    """Читает ровно указанное число байт.

    :param stream: поток ввода
    :param size: сколько байт нужно
    :return: прочитанное
    """
    chunks = []
    remaining = size
    while remaining > 0:
        chunk = stream.read(remaining)
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_frame(
    stream,
    number: int,
    width: int,
    height: int,
) -> np.ndarray:
    """Читает кадр из потока и приводит к оттенкам серого.

    Эмбеддеру нужен серый: цвет не входит в признак, а лишний канал только
    множит расчёт. Приходит кадр в том виде, в каком его отдал декодер.

    :param stream: поток ввода
    :param number: номер кадра для проверки
    :param width: ширина кадра
    :param height: высота кадра
    :return: кадр в оттенках серого
    """
    if width <= 0 or height <= 0:
        fail(f"кадр {number} имеет неразумный размер {width} на {height}")
    length = width * height * CHANNELS
    data = read_exactly(stream, length)
    if len(data) < length:
        fail(f"кадр {number} оборван: ждали {length} байт, пришло {len(data)}")
    frame = np.frombuffer(data, dtype=np.uint8).reshape(height, width, CHANNELS)
    if CHANNELS == 1:
        return frame[:, :, 0].astype(np.float32)
    # Серый по яркости: каналы идут в порядке B, G, R.
    return (
        frame[:, :, 0].astype(np.float32) * 0.114
        + frame[:, :, 1].astype(np.float32) * 0.587
        + frame[:, :, 2].astype(np.float32) * 0.299
    )


def main(argv: list[str]) -> int:
    """Работает программа.

    :param argv: аргументы командной строки
    :return: код возврата
    """
    parser = argparse.ArgumentParser(description="Эмбеддер лиц SYP")
    parser.add_argument("--model", required=True, help="путь к файлу модели")
    parser.add_argument("--provider", default="CPUExecutionProvider", help="провайдер")
    args = parser.parse_args(argv)

    embedder = Embedder(args.model, args.provider)
    # Приветствие печатается сразу, до чтения ввода. Иначе программа молча
    # ждала бы первого кадра, а бэкенд ждал бы приветствия: оба ждут друг
    # друга, и видно это только по таймауту через две минуты.
    out = sys.stdout.buffer
    out.write(embedder.greeting().encode("utf-8") + b"\n")
    out.flush()

    stream = sys.stdin.buffer
    number = 0
    while True:
        head = read_exactly(stream, REQUEST.size)
        if not head:
            break
        if len(head) < REQUEST.size:
            fail("запрос оборван на заголовке")
        reported, width, height = REQUEST.unpack(head)
        number = reported
        gray = read_frame(stream, number, width, height)
        count_raw = read_exactly(stream, COUNT.size)
        if len(count_raw) < COUNT.size:
            fail(f"кадр {number} оборван на числе лиц")
        count = COUNT.unpack(count_raw)[0]
        faces_raw = read_exactly(stream, count * REQUEST_FACE.size)
        if len(faces_raw) < count * REQUEST_FACE.size:
            fail(f"кадр {number} оборван на лицах: ждали {count}, пришло {len(faces_raw) // REQUEST_FACE.size}")
        vectors = []
        for index in range(count):
            face = REQUEST_FACE.unpack_from(faces_raw, index * REQUEST_FACE.size)
            # Одно плохое лицо не должно убивать весь проход. Раньше программа
            # на нём просто завершалась, и всё, что было дальше по потоку,
            # читалось как мусор: ответ приходил на другой кадр, и ни одно лицо
            # дальше не считалось. Здесь сбойное лицо даёт нулевой вектор, его
            # видно по нулю, а поток остаётся согласованным.
            try:
                vector = embedder.vector(gray, np.array(face, dtype=np.float32))
            # Ловим и SystemExit: расчёт вызывает fail(), а он бросает именно
            # его, а не Exception, и такой выход проскочил бы мимо перехвата.
            except (Exception, SystemExit) as error:
                print(
                    f"SYP: эмбеддер лиц: кадр {number}, лицо {index} не посчитано: "
                    f"{type(error).__name__}: {error}",
                    file=sys.stderr,
                    flush=True,
                )
                vector = np.zeros(DIMENSION, dtype=np.float32)
            vectors.append(vector)
        out.write(ANSWER.pack(number, len(vectors)))
        for vector in vectors:
            out.write(ANSWER_VECTOR.pack(*[float(v) for v in vector]))
        out.flush()


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except BrokenPipeError:
        raise SystemExit(0)
