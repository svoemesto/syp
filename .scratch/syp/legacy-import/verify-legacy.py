#!/usr/bin/env python3
"""Сверка эталонной разметки старого проекта (iGOT) с нашей моделью.

Задачи T039 (границы планов и непрерывность) и T040 (признаки кадров).
Это исследование и отчёт, а не код системы: в репозиторий переносится
только отчёт, сами данные остаются вне репозитория.

Происхождение: прототип import-s1e1.py (тот же вывод), дополненный
разбором всех 73 серий архива и сверкой карты ключевых кадров нашей
системы с признаком isIFrame старого проекта.
"""
import csv
import json
import pathlib
from collections import Counter

SRC = pathlib.Path("/home/nsa/syp/.data/legacy-iGOT")
OUT = pathlib.Path("/home/nsa/syp/.worktrees/016-sum-analysis/.scratch/syp/legacy-import")
OUT.mkdir(parents=True, exist_ok=True)

EPISODE = "S1E1"
# Путь к выгрузке отметок ключевых кадров, полученной нашим ffprobe
KEYFRAME_TIMESTAMPS = pathlib.Path("/tmp/kf-s1e1.csv")
# Частокадровая база серии S1E1: 24000/1001 кадра в секунду
FPS_NUM, FPS_DEN = 24000, 1001


def read_csv(path: pathlib.Path):
    with path.open(encoding="utf-8-sig") as f:
        return list(csv.DictReader(f, delimiter=";"))


def frame_of_millis(millis: float) -> int:
    """Номер кадра по отметке времени в миллисекундах.

    Отметка приходит из ffprobe с time_base = 1/1000, то есть в миллисекундах;
    номер кадра получается точным делением, а не округлением времени.
    """
    seconds = millis / 1000.0
    exact = seconds * FPS_NUM / FPS_DEN
    return int(exact + 0.5)


def continuity(plans: list[dict]) -> dict:
    """Разрывы, перекрытия и покрытие по списку планов в порядке следования."""
    ordered = sorted(plans, key=lambda p: p["first_frame"])
    gaps, overlaps = 0, 0
    gaps_at, overlaps_at = [], []
    for a, b in zip(ordered, ordered[1:]):
        if b["first_frame"] > a["last_frame"] + 1:
            gaps += 1
            if len(gaps_at) < 5:
                gaps_at.append([a["last_frame"] + 1, b["first_frame"] - 1])
        elif b["first_frame"] <= a["last_frame"]:
            overlaps += 1
            if len(overlaps_at) < 5:
                overlaps_at.append([a["first_frame"], a["last_frame"], b["first_frame"], b["last_frame"]])
    return {
        "gaps": gaps,
        "overlaps": overlaps,
        "first_frame": ordered[0]["first_frame"],
        "last_frame": ordered[-1]["last_frame"],
        "gap_samples": gaps_at,
        "overlap_samples": overlaps_at,
        "total_frames_covered": sum(p["last_frame"] - p["first_frame"] + 1 for p in ordered),
    }


def main():
    report = {}

    # --- 2.8 План: сегменты legacy ложатся в модель планов напрямую ---
    segments = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Segments.csv")
    plans = [
        {
            "uuid": s["segmentUUID"],
            "first_frame": int(s["firstFrameNumber"]),
            "last_frame": int(s["lastFrameNumber"]),
            "nearest_iframe": int(s["nearestIFrame"]),
        }
        for s in segments
    ]
    report["plans_s1e1"] = {
        "count": len(plans),
        "continuity": continuity(plans),
        "sample": plans[:3],
    }

    # --- весь архив: 71 005 сегментов на 73 серии ---
    archive_rows = []
    total_segments = 0
    archive_gaps = 0
    archive_overlaps = 0
    for path in sorted(SRC.glob("InputFiles/*_Segments.csv")):
        rows = read_csv(path)
        series_plans = [
            {
                "first_frame": int(r["firstFrameNumber"]),
                "last_frame": int(r["lastFrameNumber"]),
            }
            for r in rows
        ]
        total_segments += len(rows)
        check = continuity(series_plans)
        archive_gaps += check["gaps"]
        archive_overlaps += check["overlaps"]
        archive_rows.append(
            {
                "series": path.name.replace("GOT.", "").replace("_Segments.csv", ""),
                "plans": len(rows),
                "first_frame": check["first_frame"],
                "last_frame": check["last_frame"],
                "gaps": check["gaps"],
                "overlaps": check["overlaps"],
            }
        )
    report["plans_archive"] = {
        "series_count": len(archive_rows),
        "segments_total": total_segments,
        "gaps_total": archive_gaps,
        "overlaps_total": archive_overlaps,
        "plans_min": min(r["plans"] for r in archive_rows),
        "plans_max": max(r["plans"] for r in archive_rows),
        "rows": archive_rows,
    }

    # --- 2.6 Кадр: признаки по каждому кадру ---
    frames = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Frames.csv")
    counters = {
        "rows": len(frames),
        "is_iframe": sum(1 for r in frames if r["isIFrame"] == "1"),
        "is_find": sum(1 for r in frames if r["isFind"] == "1"),
        "is_final_find": sum(1 for r in frames if r["isFinalFind"] == "1"),
        "manual_add": sum(1 for r in frames if r["isManualAdd"] == "1"),
        "manual_cancel": sum(1 for r in frames if r["isManualCancel"] == "1"),
    }
    legacy_iframe_numbers = sorted(
        int(r["frameNumber"]) - 1 for r in frames if r["isIFrame"] == "1"
    )
    numbers = [int(r["frameNumber"]) for r in frames]
    report["frames_s1e1"] = {
        **counters,
        "first_frame_number_legacy": min(numbers),
        "last_frame_number_legacy": max(numbers),
        "frame_numbers_contiguous": numbers == list(range(min(numbers), max(numbers) + 1)),
        "is_find_and_final_find": sum(
            1 for r in frames if r["isFind"] == "1" and r["isFinalFind"] == "1"
        ),
        "find_not_final": sum(
            1 for r in frames if r["isFind"] == "1" and r["isFinalFind"] != "1"
        ),
        "boundary_ratio": round(counters["is_final_find"] / len(plans), 4),
    }

    # --- сверка карты ключевых кадров нашей системы с признаком isIFrame ---
    if KEYFRAME_TIMESTAMPS.exists():
        ours = sorted(
            {
                frame_of_millis(float(line.strip().rstrip(",")))
                for line in KEYFRAME_TIMESTAMPS.read_text().splitlines()
                if line.strip() and line.strip().rstrip(",") != "N/A"
            }
        )
        legacy = sorted(set(legacy_iframe_numbers))
        same = ours == legacy
        only_ours = sorted(set(ours) - set(legacy))[:10]
        only_legacy = sorted(set(legacy) - set(ours))[:10]
        report["keyframe_parity"] = {
            "ours_count": len(ours),
            "legacy_count": len(legacy),
            "identical": same,
            "only_ours": only_ours,
            "only_legacy": only_legacy,
            "first_ours": ours[:5],
            "first_legacy": legacy[:5],
            "last_ours": ours[-3:],
            "last_legacy": legacy[-3:],
        }
    else:
        report["keyframe_parity"] = {"error": f"нет файла {KEYFRAME_TIMESTAMPS}"}

    # --- 2.3 Серия: чем legacy считает число кадров ---
    declared = {
        row["fileShortName"]: row["framesCount"]
        for row in read_csv(SRC / "iGOT_videofiles.csv")
        if row["fileShortName"].endswith("S1E1")
    }
    report["series_declared_by_legacy"] = declared

    # --- сцены legacy: границ нет, только порядок и название ---
    scenes = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Scenes.csv")
    report["scenes_s1e1"] = {
        "count": len(scenes),
        "with_name": sum(1 for s in scenes if s["name"].strip()),
        "columns": list(scenes[0].keys()),
    }

    # --- персоны в планах: доля покрытия для сверки модели лиц ---
    persons = {r["personUUID"]: r["name"] for r in read_csv(SRC / "DataFiles/iGOT_Persons.csv")}
    segment_persons = {}
    for f in SRC.glob("Segments/*_persons.csv"):
        rows = read_csv(f)
        if not rows or "segmentUUID" not in rows[0]:
            continue
        for r in rows:
            segment_persons.setdefault(r["segmentUUID"], set()).add(r["personUUID"])
    covered = sum(1 for p in plans if segment_persons.get(p["uuid"]))
    report["plan_persons"] = {
        "plans_total": len(plans),
        "plans_with_persons": covered,
        "coverage_percent": round(covered / len(plans) * 100, 1),
        "persons_in_catalog": len(persons),
    }

    # --- распределение длин планов: ориентир для порогов детектора ---
    lengths = Counter(p["last_frame"] - p["first_frame"] + 1 for p in plans)
    report["plan_lengths"] = {
        "min": min(lengths),
        "max": max(lengths),
        "median": sorted(p["last_frame"] - p["first_frame"] + 1 for p in plans)[len(plans) // 2],
        "one_frame_plans": sum(1 for p in plans if p["last_frame"] == p["first_frame"]),
    }

    (OUT / "verify-legacy-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("=== ПЛАНЫ S1E1 ===")
    c = report["plans_s1e1"]["continuity"]
    print(f"  планов: {report['plans_s1e1']['count']}")
    print(f"  разрывов: {c['gaps']}, перекрытий: {c['overlaps']}")
    print(f"  покрытие: {c['first_frame']}..{c['last_frame']}")
    print(f"  кадров в сумме: {c['total_frames_covered']}")
    print("\n=== ПЛАНЫ ПО АРХИВУ ===")
    a = report["plans_archive"]
    print(f"  серий: {a['series_count']}, сегментов: {a['segments_total']}")
    print(f"  разрывов: {a['gaps_total']}, перекрытий: {a['overlaps_total']}")
    print(f"  планов на серию: от {a['plans_min']} до {a['plans_max']}")
    print("\n=== ПРИЗНАКИ КАДРОВ S1E1 ===")
    for k, v in report["frames_s1e1"].items():
        print(f"  {k}: {v}")
    print("\n=== СВЕРКА КАРТЫ КЛЮЧЕВЫХ КАДРОВ ===")
    for k, v in report["keyframe_parity"].items():
        print(f"  {k}: {v}")
    print("\n=== ПЛАНЫ И ПЕРСОНЫ ===")
    for k, v in report["plan_persons"].items():
        print(f"  {k}: {v}")
    print("\n=== ДЛИНЫ ПЛАНОВ ===")
    for k, v in report["plan_lengths"].items():
        print(f"  {k}: {v}")
    print("\n=== СЕРИЯ ПО ДАННЫМ LEGACY ===")
    print(f"  {report['series_declared_by_legacy']}")
    print(f"\n=== СЦЕНЫ LEGACY S1E1: {report['scenes_s1e1']['count']}, с названием {report['scenes_s1e1']['with_name']}")
    print(f"  столбцы сцены: {report['scenes_s1e1']['columns']}")


if __name__ == "__main__":
    main()
