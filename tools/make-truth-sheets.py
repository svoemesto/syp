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
SEGMENTS = [(0, 0, 1, 3), (1, 0, 3, 1), (2, 0, 3, 3), (3, 2, 1, 3), (4, 0, 1, 1), (5, 0, 3, 1), (6, 2, 3, 1)]


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
    """Рисует прямоугольник рамки толщиной в несколько пикселей."""
    x1 = max(0, min(x1, image.shape[1] - 1))
    x2 = max(0, min(x2, image.shape[1] - 1))
    y1 = max(0, min(y1, image.shape[0] - 1))
    y2 = max(0, min(y2, image.shape[0] - 1))
    for offset in range(width):
        image[y1 + offset : y2 + 1 - offset, x1 : x2 + 1] = color
        image[y1 + offset : y2 + 1 - offset, x1 + offset : x2 + 1 - offset] = color


def draw_number(image: np.ndarray, value: int, x: int, y: int, color: np.ndarray, scale: int = 2) -> None:
    """Пишет число рамки семисегментными цифрами."""
    digits = str(value)
    for position, char in enumerate(reversed(digits)):
        pattern = DIGITS[int(char)]
        ox = x + position * 4 * scale
        for index, (sx, sy, ex, ey) in enumerate(SEGMENTS):
            if not pattern[index]:
                continue
            for step in range(scale):
                image[oy := sy * scale + y + step, ox + sx * scale : ox + ex * scale + 1] = color
                image[oy, ox + sx * scale + step] = color
                image[oy, ox + ex * scale - step] = color


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


def merge_boxes(boxes: list[list[float]]) -> list[list[int]]:
    """Сводит рамки разных моделей в один список кандидатов."""
    merged: list[list[int]] = []
    for box in sorted(boxes, key=lambda item: -item[4]):
        current = [int(round(v)) for v in box[:4]]
        if any(iou(current, other) > 0.4 for other in merged):
            continue
        merged.append(current)
    return merged


def main() -> int:
    """Собирает листы и пишет список кандидатов по кадрам."""
    base = Path("/home/nsa/syp/.data/t076")
    results = sorted((base / "results").glob("*-t0.05.json"))
    candidates: dict[int, list[list[float]]] = {}
    for path in results:
        data = json.loads(path.read_text(encoding="utf-8"))
        for frame in data["runs"][0]["frames"]:
            candidates.setdefault(frame["n"], []).extend(frame["faces"])
    if not candidates:
        print("нет результатов на пороге 0,05")
        return 1

    raw = np.memmap(base / "frames-1080p.raw", dtype=np.uint8, mode="r")
    sheets = base / "sheets"
    sheets.mkdir(exist_ok=True)
    labels = [index for index in range(0, 996, 10)]
    manifest: dict[str, list[list[int]]] = {}
    for start in range(0, len(labels), COLS * ROWS):
        chunk = labels[start : start + COLS * ROWS]
        sheet = np.zeros((SHEET_H, SHEET_W, 3), dtype=np.uint8)
        entry: dict[str, list[list[int]]] = {}
        for position, frame_index in enumerate(chunk):
            offset = frame_index * FRAME_BYTES
            frame = np.ascontiguousarray(raw[offset : offset + FRAME_BYTES].reshape(FRAME_H, FRAME_W, 3))
            cell = downscale(frame)
            boxes = merge_boxes(candidates.get(frame_index * 89, []))
            entry[str(frame_index * 89)] = boxes
            for index, box in enumerate(boxes):
                color = COLORS[index % len(COLORS)]
                sx1 = box[0] * CELL_W // FRAME_W
                sy1 = box[1] * CELL_H // FRAME_H
                sx2 = box[2] * CELL_W // FRAME_W
                sy2 = box[3] * CELL_H // FRAME_H
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
