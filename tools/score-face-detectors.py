#!/usr/bin/env python3
# SYP — подсчёт полноты и ложных срабатываний по размеченной выборке (М-02).
#
# Считает по сохранённым результатам стенда, а не «на глаз»: и вход (результат
# прогона), и эталон (m-02-truth.json) лежат файлами, поэтому любую цифру
# отчёта можно пересчитать и проверить.
#
# Правила подсчёта, чтобы числа не расходились от смысла:
#   * рамка модели считается найденным лицом, если перекрывается с размеченным
#     лицом не хуже, чем на 0,3 по IoU;
#   * рамка, попавшая на помеченный «неразличимый» кандидат, не идёт ни в
#     найденные, ни в ложные: на таком кадре решение человека о лице принять
#     нельзя, и приписывать его модели нечестно;
#   * полнота считается по размеченным лицам, а не по кадрам: кадр с тремя
#     лицами — это три лица.

from __future__ import annotations

import argparse
import json
from pathlib import Path

IOU = 0.3


def iou(a: list[float], b: list[float]) -> float:
    """Пересечение двух рамок по отношению к объединению."""
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Разбирает аргументы подсчёта."""
    parser = argparse.ArgumentParser(description="Подсчёт полноты детектора лиц SYP")
    parser.add_argument("--truth", required=True, help="файл разметки m-02-truth.json")
    parser.add_argument("--candidates", required=True, help="файл со списком кандидатов")
    parser.add_argument("--results", required=True, help="каталог результатов стенда")
    parser.add_argument("--out", required=True, help="куда писать сводку")
    return parser.parse_args(argv)


def merge_region(boxes: list[list[float]]) -> list[float]:
    """Склеивает рамки одного объекта в одну область.

    Один и тот же глаз получает рамку от каждой модели, и четыре кандидата на
    одном лице — это одно лицо, а не четыре. Без склейки знаменатель полноты
    раздувается вчетверо, и любая модель выглядит беспомощной.
    """
    region = list(boxes[0])
    for box in boxes[1:]:
        region = [
            min(region[0], box[0]),
            min(region[1], box[1]),
            max(region[2], box[2]),
            max(region[3], box[3]),
        ]
    return region


def groups(boxes: list[list[float]]) -> list[list[float]]:
    """Группирует перекрывающиеся рамки в области объектов."""
    remaining = list(boxes)
    result: list[list[float]] = []
    while remaining:
        group = [remaining.pop(0)]
        changed = True
        while changed:
            changed = False
            rest = []
            for box in remaining:
                if any(iou(box, member) >= IOU for member in group):
                    group.append(box)
                    changed = True
                else:
                    rest.append(box)
            remaining = rest
        result.append(merge_region(group))
    return result


def inside(point: tuple[float, float], region: list[float]) -> bool:
    """Попадает ли центр рамки в область объекта."""
    return region[0] <= point[0] <= region[2] and region[1] <= point[1] <= region[3]


def main(argv: list[str]) -> int:
    """Считает полноту и ложные срабатывания по всем моделям и порогам."""
    args = parse_args(argv)
    truth = json.loads(Path(args.truth).read_text(encoding="utf-8"))
    sheets = json.loads(Path(args.candidates).read_text(encoding="utf-8"))
    candidates: dict[str, list] = {}
    for entry in sheets.values():
        candidates.update(entry)
    labels = truth["labels"]

    # Лица и неразличимые объекты: кандидаты склеиваются в области.
    faces_by_frame: dict[int, list] = {}
    undecidable_by_frame: dict[int, list] = {}
    for number, label in labels.items():
        boxes = candidates[number]
        faces_by_frame[int(number)] = groups([boxes[index - 1] for index in label["faces"]])
        undecidable_by_frame[int(number)] = groups([boxes[index - 1] for index in label["undecidable"]])
    total_faces = sum(len(value) for value in faces_by_frame.values())

    rows = []
    # Развёртка по порогам считается по одному прогону на модель — самому
    # низкому: рамки с большей уверенностью входят в этот набор, а фильтр по
    # порогу делается здесь. Повторный разбор того же прогона дал бы те же
    # строки по четыре раза.
    for path in sorted(Path(args.results).glob("*-t0.05.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        model = path.stem.rsplit("-t", 1)[0]
        by_number = {frame["n"]: frame for frame in data["runs"][0]["frames"]}
        source = data["score_threshold"]
        for threshold in (0.05, 0.2, 0.4, 0.6, 0.8, 0.9):
            found: set[tuple[int, int]] = set()
            false_positives = 0
            undecidable = 0
            detections = 0
            for number, label in labels.items():
                face_regions = faces_by_frame[int(number)]
                for face in by_number.get(int(number), {}).get("faces", []):
                    if face[4] < max(threshold, source):
                        continue
                    detections += 1
                    centre = ((face[0] + face[2]) / 2.0, (face[1] + face[3]) / 2.0)
                    hits = [i for i, region in enumerate(face_regions) if inside(centre, region)]
                    if hits:
                        for hit in hits:
                            found.add((int(number), hit))
                        continue
                    if any(inside(centre, region) for region in undecidable_by_frame[int(number)]):
                        undecidable += 1
                        continue
                    false_positives += 1
            rows.append(
                {
                    "model": model,
                    "threshold": threshold,
                    "faces_total": total_faces,
                    "faces_found": len(found),
                    "recall": round(len(found) / total_faces, 4) if total_faces else 0.0,
                    "false_positives": false_positives,
                    "on_undecidable": undecidable,
                    "detections": detections,
                }
            )
    # Рабочий порог 0,30 меряется отдельным прогоном: в развёртке его нет,
    # и подставлять его «примерно между 0,2 и 0,4» нельзя — это было бы
    # выдуманным числом.
    for path in sorted(Path(args.results).glob("*-t0.3.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        model = path.stem.rsplit("-t", 1)[0]
        by_number = {frame["n"]: frame for frame in data["runs"][0]["frames"]}
        found = set()
        false_positives = 0
        undecidable = 0
        detections = 0
        for number, label in labels.items():
            face_regions = faces_by_frame[int(number)]
            for face in by_number.get(int(number), {}).get("faces", []):
                detections += 1
                centre = ((face[0] + face[2]) / 2.0, (face[1] + face[3]) / 2.0)
                hits = [i for i, region in enumerate(face_regions) if inside(centre, region)]
                if hits:
                    for hit in hits:
                        found.add((int(number), hit))
                    continue
                if any(inside(centre, region) for region in undecidable_by_frame[int(number)]):
                    undecidable += 1
                    continue
                false_positives += 1
        rows.append(
            {
                "model": model,
                "threshold": 0.3,
                "faces_total": total_faces,
                "faces_found": len(found),
                "recall": round(len(found) / total_faces, 4) if total_faces else 0.0,
                "false_positives": false_positives,
                "on_undecidable": undecidable,
                "detections": detections,
            }
        )

    summary = {
        "labelled_frames": truth["viewed_frames"],
        "faces_in_truth": total_faces,
        "rows": rows,
    }
    Path(args.out).write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    for row in rows:
        print(
            "%-14s порог %.2f: найдено %d из %d (%.1f%%), ложных %d, на неразличимых %d, всего рамок %d"
            % (
                row["model"],
                row["threshold"],
                row["faces_found"],
                row["faces_total"],
                100.0 * row["recall"],
                row["false_positives"],
                row["on_undecidable"],
                row["detections"],
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(__import__("sys").argv[1:]))
