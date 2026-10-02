#!/usr/bin/env python3
# SYP — разметка кадров для замера М-02: листы с рамками-кандидатами.
#
# Зачем это нужно. Полноту детектора нельзя посчитать по координатам на глаз:
# «лицо есть, но не найдено» — это утверждение о картинке. Лист показывает
# кадр целиком и все рамки-кандидаты, найденные любой из моделей на низком
# пороге, с номерами. Человек смотрит на лист и отвечает на два вопроса:
# какие из рамок — настоящие лица и есть ли лицо, которого ни одна модель не
# предложила.
#
# Рисование идёт на numpy и пишется в PNG своим кодом: в среде замера нет ни
# opencv, ни Pillow, а ставить их ради листа было бы лишней зависимостью.

from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path

import numpy as np

FRAME_W, FRAME_H = 1920, 1080
FRAME_BYTES = FRAME_W * FRAME_H * 3
CELL_W, CELL_H = 640, 360
COLS, ROWS = 3, 2
SHEET_W, SHEET_H = CELL_W * COLS, CELL_H * ROWS
OUTLINE = np.array([0, 0, 0], dtype=np.uint8)
COLORS = np.array(
    [
        [255, 64, 64],
        [64, 255, 64],
        [64, 128, 255],
        [255, 255, 64],
        [255, 64, 255],
        [64, 255, 255],
        [255, 160, 32],
        [200, 200, 200],
    ],
    dtype=np.uint8,
)

# Семисегментные цифры: индекс рамки пишется прямо на кадре, чтобы решение
# человека относилось к конкретной рамке, а не к «третьей слева».
DIGITS = {
    0: (1, 1, 1, 1, 1, 1, 0),
    1: (0, 1, 1, 0, 0, 0, 0),
    2: (1, 1, 0, 1, 1, 0, 1),
    3: (1, 1, 1, 1, 0, 0, 1),
    4: (0, 1, 1, 0, 0, 1, 1),
    5: (1, 0, 1, 1, 0, 1, 1),
    6: (1, 0, 1, 1, 1, 1, 1),
    7: (1, 1, 1, 0, 0, 0, 0),
    8: (1, 1, 1, 1, 1, 1, 1),
    9: (1, 1, 1, 1, 0, 1, 1),
}
# Сегменты семисегментной цифры в сетке 2 на 4 ячейки: (x0, y0, x1, y1).
SEGMENTS = [
    (0, 0, 2, 0),
    (2, 0, 2, 2),
    (2, 2, 2, 4),
    (0, 4, 2, 4),
    (0, 2, 0, 4),
    (0, 0, 0, 2),
    (0, 2, 2, 2),
]


def write_png(path: Path, image: np.ndarray) -> None:
    """Записывает изображение RGB в PNG без внешних библиотек."""
    height, width = image.shape[0], image.shape[1]
    raw = b"".join(b"\x00" + image[row].tobytes() for row in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def downscale(frame: np.ndarray) -> np.ndarray:
    """Уменьшает кадр усреднением по площади до размера ячейки листа."""
    factor_h = frame.shape[0] // CELL_H
    factor_w = frame.shape[1] // CELL_W
    trimmed = frame[: CELL_H * factor_h, : CELL_W * factor_w]
    return trimmed.reshape(CELL_H, factor_h, CELL_W, factor_w, 3).mean(axis=(1, 3)).astype(np.uint8)


def draw_rect(image: np.ndarray, x1: int, y1: int, x2: int, y2: int, color: np.ndarray, width: int = 2) -> None:
    """Рисует рамку контуром, а не заливкой.

    Заливка здесь не годится: рамка закрыла бы собой кадр, и решение
    человека было бы принято вслепую.

    :param image: ячейка листа
    :param x1 левая граница
    :param y1 верхняя граница
    :param x2 правая граница
    :param y2 нижняя граница
    :param color цвет контура
    :param width толщина контура
    """
    x1 = max(0, min(x1, image.shape[1] - 1))
    x2 = max(0, min(x2, image.shape[1] - 1))
    y1 = max(0, min(y1, image.shape[0] - 1))
    y2 = max(0, min(y2, image.shape[0] - 1))
    if x2 <= x1 or y2 <= y1:
        return
    for step in range(width):
        top = min(y1 + step, y2)
        bottom = max(y2 - step, y1)
        left = min(x1 + step, x2)
        right = max(x2 - step, x1)
        image[top, x1 : x2 + 1] = color
        image[bottom, x1 : x2 + 1] = color
        image[y1 : y2 + 1, left] = color
        image[y1 : y2 + 1, right] = color


def draw_number(image: np.ndarray, value: int, x: int, y: int, color: np.ndarray, scale: int = 3) -> None:
    """Пишет число рамки семисегментными цифрами с чёрным контуром.

    Контур обязателен: кадры в серии и тёмные, и заснеженные, и цифра без
    контура на половине из них не читается. Номер рисуется слева от рамки,
    если справа места нет: иначе решение человека относилось бы к обрезанной
    цифре.

    :param image: ячейка листа
    :param value номер рамки
    :param x левая граница цифры
    :param y верхняя граница цифры
    :param color цвет цифры
    :param scale толщина сегмента
    """
    digits = str(value)
    needed = len(digits) * 3 * scale + scale
    if x + needed > image.shape[1]:
        x = max(image.shape[1] - needed - 1, 0)
    y = max(min(y, image.shape[0] - 5 * scale - 1), 0)
    for position, char in enumerate(reversed(digits)):
        pattern = DIGITS[int(char)]
        ox = x + position * 3 * scale
        for index, (sx0, sy0, sx1, sy1) in enumerate(SEGMENTS):
            if not pattern[index]:
                continue
            left = ox + min(sx0, sx1) * scale
            right = ox + max(sx0, sx1) * scale
            top = y + min(sy0, sy1) * scale
            bottom = y + max(sy0, sy1) * scale
            image[max(top - 1, 0) : bottom + 2, max(left - 1, 0) : right + 2] = OUTLINE
            image[top : bottom + 1, left : right + 1] = color


def iou(a: list[int], b: list[int]) -> float:
    """Пересечение рамок по отношению к объединению."""
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / float(area_a + area_b - inter)


def plausible(box: list[int]) -> bool:
    """Отсеивает рамки, которые лицом быть не могут.

    Отсечка по размеру и пропорциям нужна не для красоты: часть моделей на
    тёмных и пустых кадрах выдаёт рамку во весь кадр, и такая рамка, нарисованная
    на листе, закрыла бы собой всё, что должна показать. Настоящее лицо в
    кадре 1920 на 1080 занимает от двадцати до семисот пикселей по меньшей
    стороне и не бывает шире в два с половиной раза выше или уже.
    """
    width = box[2] - box[0]
    height = box[3] - box[1]
    if width < 20 or height < 20:
        return False
    if width > FRAME_W * 0.8 or height > FRAME_H * 0.9:
        return False
    ratio = width / float(height)
    return 0.35 <= ratio <= 2.8


def merge_boxes(boxes: list[list[float]]) -> list[list[int]]:
    """Сводит рамки разных моделей в один список кандидатов."""
    merged: list[list[int]] = []
    for box in sorted(boxes, key=lambda item: -item[4]):
        current = [int(round(v)) for v in box[:4]]
        current[0] = max(current[0], 0)
        current[1] = max(current[1], 0)
        current[2] = min(current[2], FRAME_W)
        current[3] = min(current[3], FRAME_H)
        if not plausible(current):
            continue
        if any(iou(current, other) > 0.4 for other in merged):
            continue
        merged.append(current)
    return merged


def main() -> int:
    """Собирает листы и пишет список кандидатов по кадрам."""
    base = Path("/home/nsa/syp/.data/t076")
    # Кандидаты собираются с порога, ниже которого рамки заведомо мусор:
    # при 0,05 на кадр приходится около семидесяти рамок-кандидатов, и
    # разбирать их вручную бессмысленно — решение человека должно быть о
    # рамках, которые хоть одна модель считает лицом.
    candidate_floor = 0.30
    per_frame_limit = 8
    results = sorted((base / "results").glob("*-t0.05.json"))
    candidates: dict[int, list[list[float]]] = {}
    for path in results:
        data = json.loads(path.read_text(encoding="utf-8"))
        for frame in data["runs"][0]["frames"]:
            for face in frame["faces"]:
                if face[4] >= candidate_floor:
                    candidates.setdefault(frame["n"], []).append(face)
    if not candidates:
        print("нет результатов на пороге 0,05")
        return 1

    raw = np.memmap(base / "frames-1080p.raw", dtype=np.uint8, mode="r")
    sheets = base / "sheets"
    sheets.mkdir(exist_ok=True)
    # Размечаемая выборка — каждый двадцатый кадр из 996, то есть пятьдесят
    # кадров, равномерно разнесённых по всей серии. Ровномерность важна:
    # первые кадры серии почти без лиц, и выборка из начала дала бы
    # неверное представление о полноте.
    labels = [index for index in range(0, 996, 20)]
    manifest: dict[str, list[list[int]]] = {}
    for start in range(0, len(labels), COLS * ROWS):
        chunk = labels[start : start + COLS * ROWS]
        sheet = np.zeros((SHEET_H, SHEET_W, 3), dtype=np.uint8)
        entry: dict[str, list[list[int]]] = {}
        for position, frame_index in enumerate(chunk):
            offset = frame_index * FRAME_BYTES
            frame = np.ascontiguousarray(raw[offset : offset + FRAME_BYTES].reshape(FRAME_H, FRAME_W, 3))
            cell = downscale(frame)
            boxes = merge_boxes(candidates.get(frame_index * 89, []))[:per_frame_limit]
            entry[str(frame_index * 89)] = boxes
            for index, box in enumerate(boxes):
                color = COLORS[index % len(COLORS)]
                sx1 = box[0] * CELL_W // FRAME_W
                sy1 = box[1] * CELL_H // FRAME_H
                sx2 = box[2] * CELL_W // FRAME_W
                sy2 = box[3] * CELL_H // FRAME_H
                if sx2 - sx1 < 8 or sy2 - sy1 < 8:
                    # Мелкое лицо в общем плане не помещается в рамку: без
                    # метки его просто не видно, а это ровно то лицо, ради
                    # которого затевался весь замер. Метка всегда одного
                    # размера — иначе огромная ложная рамка залила бы весь
                    # кадр и закрыла собой всё остальное.
                    cx = max(min((sx1 + sx2) // 2, CELL_W - 9), 0)
                    cy = max(min((sy1 + sy2) // 2, CELL_H - 9), 0)
                    cell[cy - 4 : cy + 5, cx - 4 : cx + 5] = color
                draw_rect(cell, sx1, sy1, sx2, sy2, color)
                draw_number(cell, index + 1, max(sx1, 0), max(sy1 - 2, 0), color)
            row, column = divmod(position, COLS)
            sheet[row * CELL_H : (row + 1) * CELL_H, column * CELL_W : (column + 1) * CELL_W] = cell
        name = "sheet-%03d.png" % (start // (COLS * ROWS))
        write_png(sheets / name, sheet)
        manifest[name] = entry
    (base / "sheets.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(len(v) for entry in manifest.values() for v in entry.values())
    print("листов: %d, кадров: %d, кандидатов: %d" % (len(manifest), len(labels), total))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
