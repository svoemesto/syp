"""Меряет разделяющую силу простейшего признака лица.

Зачем: прежде чем строить группировку, надо узнать, годится ли простейший
признак вообще. Модель эмбеддингов недоступна (InsightFace требует лицензии,
обученной модели нет), поэтому начать приходится с признака, который не обучает
никто: уменьшенное изображение лица.

Проверка без размеченных данных. В кадре лица почти всегда разные люди — в
кадре, где их пятьдесят, это толпа, а не один человек. Значит расстояния внутри
одного кадра должны быть больше, чем между лицами из разных кадров, если признак
что-то различает. Если внутрикадровые и случайные расстояния неразличимы, признак
бесполезен, и об этом надо сказать сразу, а не чинить молча.

Запуск: python3 measure_face_descriptor.py
"""

import math
import pathlib
import random
import sys

DATA = pathlib.Path(__file__).parent
# Признак: лицо, уменьшенное до 16 на 16 серых, с выравниванием по среднему и
# нормировкой. Ровно то, что считается без обучения и без зависимостей.
SIDE = 16


def read_pgm(path):
    """Читает полутоновый PGM, как его пишет ffmpeg."""
    raw = path.read_bytes()
    parts = []
    pos = 0
    # Заголовок: магическое число, размеры, максимум — через пробелы и переводы.
    while len(parts) < 4:
        while pos < len(raw) and raw[pos: pos + 1].isspace():
            pos += 1
        if raw[pos: pos + 1] == b"#":
            while pos < len(raw) and raw[pos: pos + 1] != b"\n":
                pos += 1
            continue
        start = pos
        while pos < len(raw) and not raw[pos: pos + 1].isspace():
            pos += 1
        parts.append(raw[start:pos])
    pos += 1
    magic, width, height, _ = parts[0], int(parts[1]), int(parts[2]), int(parts[3])
    if magic != b"P5":
        raise SystemExit(f"не тот формат: {magic!r}")
    return width, height, raw[pos: pos + width * height]


def sample_box(pixels, width, height, box):
    """Берёт значение в центре каждой ячейки сетки SIDE на SIDE.

    Выборка по центрам, а не усреднением: у рамки лица в кадре может быть
    23 на 32 пикселя, и усреднение по ячейкам оставляет часть ячеек пустыми —
    делить на ноль. Центр ячейки всегда попадает в рамку.
    """
    x1, y1, x2, y2 = box
    x1 = max(0, min(width - 1, x1))
    x2 = max(x1 + 1, min(width, x2))
    y1 = max(0, min(height - 1, y1))
    y2 = max(y1 + 1, min(height, y2))
    values = []
    for row in range(SIDE):
        y = y1 + (row * 2 + 1) * (y2 - y1) // (2 * SIDE)
        y = max(y1, min(y2 - 1, y))
        base = y * width
        for col in range(SIDE):
            x = x1 + (col * 2 + 1) * (x2 - x1) // (2 * SIDE)
            x = max(x1, min(x2 - 1, x))
            values.append(pixels[base + x])
    return values


def normalise(values):
    """Вычитает среднее и делит на длину: так признак не зависит от освещения."""
    mean = sum(values) / len(values)
    centred = [v - mean for v in values]
    norm = math.sqrt(sum(v * v for v in centred)) or 1.0
    return [v / norm for v in centred]


def distance(a, b):
    """Расстояние между нормированными признаками."""
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def load_frame(path):
    """Читает кадр и его рамки лиц."""
    width, height, pixels = read_pgm(path)
    boxes = []
    for line in path.with_name("boxes_" + path.stem.replace("frame", "") + ".txt").read_text().split("\n"):
        parts = line.split()
        if len(parts) == 4:
            boxes.append(tuple(int(v) for v in parts))
    return width, height, pixels, boxes


def main():
    frames = []
    for path in sorted(DATA.glob("frame*.pgm")):
        width, height, pixels, boxes = load_frame(path)
        descriptors = [normalise(sample_box(pixels, width, height, b)) for b in boxes]
        frames.append((path.name, descriptors))
        print(f"{path.name}: {len(descriptors)} лиц")

    # Внутрикадровые: лица из одного кадра, то есть разные люди.
    inside = []
    for _, ds in frames:
        for i in range(len(ds)):
            for j in range(i + 1, len(ds)):
                inside.append(distance(ds[i], ds[j]))
    # Случайные пары из разных кадров — база, с которой сравниваем.
    between = []
    rng = random.Random(20261003)
    for _ in range(4000):
        a, b = rng.sample(frames, 2)
        between.append(distance(rng.choice(a[1]), rng.choice(b[1])))
    if not inside or not between:
        raise SystemExit("не хватило лиц для замера")

    def share(values):
        values = sorted(values)
        n = len(values)
        return values[n // 10], values[n // 2], values[9 * n // 10]

    inside_10, inside_50, inside_90 = share(inside)
    between_10, between_50, between_90 = share(between)
    print()
    print("расстояние внутри кадра (разные люди):")
    print(f"   10% {inside_10:.3f}   половина {inside_50:.3f}   90% {inside_90:.3f}")
    print("расстояние между кадрами (смесь людей):")
    print(f"   10% {between_10:.3f}   половина {between_50:.3f}   90% {between_90:.3f}")
    print()
    if inside_10 > between_90:
        print("ВЕРДИКТ: признак различает. Внутрикадровые пары дальше межкадровых.")
        return 0
    if inside_50 > between_50:
        print("ВЕРДИКТ: признак различает слабо. Половина внутрикадровых дальше половины межкадровых.")
        return 1
    print("ВЕРДИКТ: признак не различает. Строить на нём группировку бессмысленно.")
    return 2


if __name__ == "__main__":
    sys.exit(main())
