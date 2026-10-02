#!/usr/bin/env python3
"""Сверка границ автоматики с эталоном старого проекта (задача T060).

Разбирает вывод `ffmpeg -vf scdet` на `GOT.S01E01` и сопоставляет найденные
границы с 1028 эталоными планами старого проекта iGOT. В репозиторий
переносится только отчёт, сами данные старого проекта остаются вне него.

Три вещи, ради которых написан скрипт:

1. **Разная нумерация кадров.** Старый проект нумерует кадры с единицы, наша
   система — с нуля (ADR-0001, research.md Т-12). Без сдвига на единицу
   каждая граница «разошлась бы» с эталоном, и число выглядело бы
   катастрофическим.
2. **Точное совпадение номеров — слишком строгое мерило.** Человек ставит
   границу по монтажной склейке, детектор — по оценке сходства кадров. Поэтому
   рядом с точным совпадением считается и близость: сколько эталонных границ
   нашлось в пределах заданного числа кадров.
3. **Числа, а не ощущения.** Скрипт печатает и сохраняет конкретные
   количества; выводы без них не делаются.

Запуск:
    python3 .scratch/syp/legacy-import/scene-parity.py \
        --scdet-log /home/nsa/syp/.data/marking/scdet-threshold4.log
"""
import argparse
import csv
import json
import pathlib
import re
from collections import Counter
from decimal import ROUND_HALF_UP, Decimal

LEGACY = pathlib.Path("/home/nsa/syp/.data/legacy-iGOT")
LEGACY_EPISODE = "S1E1"
FRAME_COUNT = 88_643
# Частокадровая база серии S1E1: 24000/1001 кадра в секунду.
FPS_NUM, FPS_DEN = 24000, 1001

SCORE = re.compile(r"lavfi\.scd\.score:\s*([0-9.]+)\s*,\s*lavfi\.scd\.time:\s*([0-9.]+)")


def read_csv(path: pathlib.Path) -> list[dict]:
    with path.open(encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def frame_of_seconds(seconds: str) -> int:
    """Номер кадра по отметке времени детектора.

    Деление точное, округление полу bankers, а не «как придётся»: на длинной
    серии ошибка округления разъезжается на десятки кадров (ADR-0001).
    """
    exact = Decimal(seconds) * FPS_NUM / Decimal(FPS_DEN)
    return int(exact.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def read_scores(log: pathlib.Path) -> list[tuple[int, float]]:
    """Разбирает вывод детектора в список (кадр, оценка) по возрастанию."""
    scores: list[tuple[int, float]] = []
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        found = SCORE.search(line)
        if not found:
            continue
        scores.append((frame_of_seconds(found.group(2)), float(found.group(1))))
    return sorted(scores)


def sections(starts: list[int]) -> list[tuple[int, int]]:
    """Разбирает точки границ в участки, покрывающие серию целиком."""
    ordered = sorted(set(starts))
    if not ordered or ordered[0] != 0:
        ordered = [0] + ordered
    result = []
    for index, first in enumerate(ordered):
        last = ordered[index + 1] - 1 if index + 1 < len(ordered) else FRAME_COUNT - 1
        if last >= first:
            result.append((first, last))
    return result


def coverage(parts: list[tuple[int, int]]) -> dict:
    """Разрывы, перекрытия и границы покрытия."""
    gaps, overlaps = 0, 0
    for first, second in zip(parts, parts[1:]):
        if second[0] > first[1] + 1:
            gaps += 1
        elif second[0] <= first[1]:
            overlaps += 1
    return {
        "count": len(parts),
        "first_frame": parts[0][0] if parts else None,
        "last_frame": parts[-1][1] if parts else None,
        "gaps": gaps,
        "overlaps": overlaps,
    }


def nearest_distance(value: int, pool: list[int]) -> int | None:
    """Расстояние от числа до ближайшего из пула."""
    if not pool:
        return None
    return min(abs(value - other) for other in pool)


def compare(reference: list[int], ours: list[int], tolerance: int) -> dict:
    """Сверяет наши границы с эталонными: точно и в пределах отклонения."""
    reference_set, our_set = set(reference), set(ours)
    matched_exact = len(reference_set & our_set)
    within = [d for d in (nearest_distance(value, ours) for value in reference) if d is not None and d <= tolerance]
    distances = [nearest_distance(value, ours) for value in reference if nearest_distance(value, ours) is not None]
    histogram = Counter()
    for distance in distances:
        bucket = 0 if distance == 0 else min(distance, 50)
        histogram[bucket] += 1
    return {
        "reference_count": len(reference_set),
        "ours_count": len(our_set),
        "matched_exact": matched_exact,
        "reference_missing": len(reference_set - our_set),
        "ours_extra": len(our_set - reference_set),
        "tolerance": tolerance,
        "reference_within_tolerance": len(within),
        "reference_within_tolerance_share": round(100.0 * len(within) / len(reference_set), 2) if reference_set else 0.0,
        "max_distance": max(distances) if distances else None,
        "median_distance": sorted(distances)[len(distances) // 2] if distances else None,
        "distance_histogram_0_50": {str(k): histogram[k] for k in sorted(histogram)},
    }


def legacy_scenes() -> dict:
    """Считает названные сцены S1E1 в старом проекте.

    Файл сцен хранит порядковый номер и название, но **не** границы кадров,
    поэтому сверять наши границы сцен с эталонными по нему нечем: сопоставимо
    только число сцен, и это отмечено прямо в отчёте.
    """
    rows = read_csv(LEGACY / "InputFiles" / f"GOT.{LEGACY_EPISODE}_Scenes.csv")
    named = [row for row in rows if str(row.get("name", "")).strip()]
    return {
        "rows": len(rows),
        "named": len(named),
        "has_frame_bounds": False,
        "note": "файл сцен старого проекта не содержит границ кадров: сверка границ сцен невозможна",
    }


def grid(scores: list[tuple[int, float]], reference: list[int], thresholds: list[float]) -> list[dict]:
    """Считает сетку порогов по одному прогону детектора.

    Оценка кадра от порога не зависит: порог влияет только на отбор
    (ADR-0005, research.md Т-05). Поэтому десять значений порога считаются из
    одного и того же прогона, а не из десяти прогонов, и числа сетки
    сопоставимы между собой без оговорок об условиях.
    """
    rows = []
    for threshold in thresholds:
        starts = [0] + [frame for frame, score in scores if score >= threshold]
        parts = sections(starts)
        verdict = compare(reference, starts, tolerance=5)
        rows.append(
            {
                "threshold": threshold,
                "boundaries": len(starts) - 1,
                "sections": len(parts),
                "gaps": coverage(parts)["gaps"],
                "overlaps": coverage(parts)["overlaps"],
                "matched_exact": verdict["matched_exact"],
                "reference_missing": verdict["reference_missing"],
                "ours_extra": verdict["ours_extra"],
                "reference_within_5_frames": verdict["reference_within_tolerance"],
            }
        )
    return rows


def frame_flags() -> dict:
    """Считает признаки кадров S1E1 в старом проекте."""
    rows = read_csv(LEGACY / "InputFiles" / f"GOT.{LEGACY_EPISODE}_Frames.csv")
    counts = Counter()
    for row in rows:
        for column in ("isIFrame", "isFind", "isFinalFind", "isManualAdd", "isManualCancel"):
            if str(row.get(column, "")).strip().lower() in ("1", "true", "yes"):
                counts[column] += 1
    counts["rows"] = len(rows)
    return dict(counts)


def main() -> None:
    parser = argparse.ArgumentParser(description="Сверка границ автоматики с эталоном старого проекта")
    parser.add_argument("--scdet-log", type=pathlib.Path, required=True, help="вывод ffmpeg с scdet")
    parser.add_argument("--out", type=pathlib.Path, required=True, help="куда записать отчёт в JSON")
    parser.add_argument("--scene-threshold", type=float, default=8.0)
    parser.add_argument("--shot-threshold", type=float, default=4.0)
    parser.add_argument(
        "--grid",
        type=float,
        nargs="*",
        default=[],
        help="значения порога для сетки М-03; считаются из того же прогона",
    )
    args = parser.parse_args()

    scores = read_scores(args.scdet_log)
    if not scores:
        raise SystemExit(f"в {args.scdet_log} нет ни одной строки с оценкой детектора")

    scene_starts = [0] + [frame for frame, score in scores if score >= args.scene_threshold]
    shot_starts = [0] + [frame for frame, score in scores if score >= args.shot_threshold]
    scene_parts = sections(scene_starts)
    shot_parts = sections(shot_starts)

    # Старый проект нумерует кадры с единицы, наша система — с нуля.
    legacy_plans = read_csv(LEGACY / "InputFiles" / f"GOT.{LEGACY_EPISODE}_Segments.csv")
    legacy_plan_starts = sorted({int(row["firstFrameNumber"]) - 1 for row in legacy_plans})

    report = {
        "episode": LEGACY_EPISODE,
        "frame_count": FRAME_COUNT,
        "detector": {
            "scores_read": len(scores),
            "scene_threshold": args.scene_threshold,
            "shot_threshold": args.shot_threshold,
            "scene_boundaries": len(scene_starts) - 1,
            "shot_boundaries": len(shot_starts) - 1,
        },
        "our_scenes": coverage(scene_parts),
        "our_shots": coverage(shot_parts),
        "legacy_plans": {
            "count": len(legacy_plans),
            "unique_starts": len(legacy_plan_starts),
            "continuity": coverage(
                sorted(
                    (int(row["firstFrameNumber"]) - 1, int(row["lastFrameNumber"]) - 1)
                    for row in legacy_plans
                )
            ),
        },
        "parity_shot": compare(legacy_plan_starts, shot_starts, tolerance=5),
        "legacy_frame_flags": frame_flags(),
        "legacy_scenes": legacy_scenes(),
    }
    if args.grid:
        report["grid"] = grid(scores, legacy_plan_starts, sorted(args.grid))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
