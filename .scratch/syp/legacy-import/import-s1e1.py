#!/usr/bin/env python3
"""
Прототип импорта разметки старого проекта (iGOT) в модель SYP.

Назначение: показать, что именно из найденных данных ложится в нашу модель
и чего в ней не будет. Это исследование, не код системы.

Источник: /home/nsa/syp-data/legacy-iGOT (выгружено с 10.0.1.2)
Целевая модель: specs/001-first-vertical-slice/data-model.md (ветка 007)
"""
import csv
import json
import pathlib
import subprocess
from collections import defaultdict

SRC = pathlib.Path("/home/nsa/syp-data/legacy-iGOT")
OUT = pathlib.Path("/home/nsa/syp/.scratch/syp/legacy-import")
OUT.mkdir(parents=True, exist_ok=True)

EPISODE = "S1E1"
SOURCE_FILE = "GOT.S01E01.BDRip.1080p.mkv"


def read_csv(path: pathlib.Path):
    with path.open(encoding="utf-8-sig") as f:
        return list(csv.DictReader(f, delimiter=";"))


def ffprobe(path: str) -> dict:
    """Параметры исходника — их нам даёт собственный код, а не legacy."""
    cmd = [
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,profile,width,height,pix_fmt,r_frame_rate,nb_frames",
        "-show_entries", "format=duration,size,format_name",
        "-of", "json", path,
    ]
    return json.loads(subprocess.run(cmd, capture_output=True, text=True).stdout)


def main():
    report = {}

    # --- 2.3 Серия: параметры берём из своего ffprobe ---
    src = f"/disks/HDD_16Tb_Clouds/GOT/GOT.S01/{SOURCE_FILE}"
    probe = ffprobe(src)
    stream = probe["streams"][0]
    fmt = probe["format"]
    num, den = (int(x) for x in stream["r_frame_rate"].split("/"))
    duration = float(fmt["duration"])
    frames = round(duration * num / den)

    series = {
        "order": 1,
        "name": "S1E1",
        "source_path": f"GOT.S01/{SOURCE_FILE}",
        "size_bytes": int(fmt["size"]),
        "frame_count": frames,
        "fps_num": num,
        "fps_den": den,
        "width": stream["width"],
        "height": stream["height"],
        "codec": stream["codec_name"],
        "profile": stream.get("profile"),
        "pix_fmt": stream["pix_fmt"],
    }
    report["series"] = series
    report["series_legacy_says"] = {
        row["framesCount"]: row["fileTitle"]
        for row in read_csv(SRC / "iGOT_videofiles.csv")
        if row["fileShortName"].endswith("S1E1")
    }

    # --- 2.8 План: сегменты legacy ложатся напрямую ---
    segments = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Segments.csv")
    plans = [
        {
            "uuid": s["segmentUUID"],
            "first_frame": int(s["firstFrameNumber"]),
            "last_frame": int(s["lastFrameNumber"]),
            "nearest_iframe": int(s["nearestIFrame"]),
            "size": "NONE",              # ADR-0003: вычисляется автоматически
            "size_origin": "AUTO",
            "boundary_origin": "AUTO",
        }
        for s in segments
    ]
    report["plans_count"] = len(plans)
    report["plans_sample"] = plans[:3]

    # проверка непрерывности: планы должны покрывать серию без разрывов и дыр
    gaps, overlaps = 0, 0
    for a, b in zip(plans, plans[1:]):
        if b["first_frame"] != a["last_frame"] + 1:
            (gaps if b["first_frame"] > a["last_frame"] + 1 else overlaps).__int__()
            if b["first_frame"] > a["last_frame"] + 1:
                gaps += 1
            else:
                overlaps += 1
    report["plans_continuity"] = {
        "gaps": gaps,
        "overlaps": overlaps,
        "first_frame": plans[0]["first_frame"],
        "last_frame": plans[-1]["last_frame"],
    }

    # --- 2.6 Кадр: признаки по каждому кадру ---
    frames_rows = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Frames.csv")
    report["frames"] = {
        "rows": len(frames_rows),
        "is_iframe": sum(1 for r in frames_rows if r["isIFrame"] == "1"),
        "is_find": sum(1 for r in frames_rows if r["isFind"] == "1"),
        "is_final_find": sum(1 for r in frames_rows if r["isFinalFind"] == "1"),
        "manual_add": sum(1 for r in frames_rows if r["isManualAdd"] == "1"),
        "manual_cancel": sum(1 for r in frames_rows if r["isManualCancel"] == "1"),
    }

    # --- Сцены: названия есть, границ нет ---
    scenes = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Scenes.csv")
    report["scenes"] = {
        "count": len(scenes),
        "with_name": sum(1 for s in scenes if s["name"].strip()),
        "sample_names": [s["name"] for s in scenes[:5]],
        "has_frame_bounds": False,
    }

    # --- События: персоны в диапазоне кадров ---
    events = read_csv(SRC / f"InputFiles/GOT.{EPISODE}_Events.csv")
    seg_first = {p["uuid"]: p["first_frame"] for p in plans}
    seg_last = {p["uuid"]: p["last_frame"] for p in plans}
    persons_idx = {r["personUUID"]: r["name"] for r in read_csv(SRC / "DataFiles/iGOT_Persons.csv")}

    # В каталоге Segments четыре разных типа связей, а не один.
    # Главный для нас — сегмент (план) <-> персона.
    segment_persons = defaultdict(set)
    scene_persons = defaultdict(set)
    group_members = defaultdict(set)
    event_persons = defaultdict(set)
    for f in SRC.glob("Segments/*_persons.csv"):
        rows = read_csv(f)
        if not rows:
            continue
        head = set(rows[0].keys())
        for r in rows:
            if "segmentUUID" in head:
                segment_persons[r["segmentUUID"]].add(r["personUUID"])
            elif "sceneUUID" in head:
                scene_persons[r["sceneUUID"]].add(r["personUUID"])
            elif "groupUUID" in head:
                group_members[r["groupUUID"]].add(r["personUUID"])
            elif "eventUUID" in head:
                event_persons[r["eventUUID"]].add(r["personUUID"])
    report["legacy_relations"] = {
        "segment_person": len(segment_persons),
        "scene_person": len(scene_persons),
        "event_person": len(event_persons),
        "groups": len(group_members),
    }

    events_out = []
    for e in events:
        start = int(e["frameStart"])
        end = int(e["frameEnd"])
        names = sorted(
            persons_idx.get(u, "?") for u in event_persons.get(e["eventUUID"], set())
        )
        events_out.append({
            "name": e["eventName"],
            "first_frame": start,
            "last_frame": end,
            "persons": names,
        })
    # привязка персон к планам (сегментам) — то, что у нас заменяет модель лиц
    covered = 0
    for pl in plans:
        names = sorted(persons_idx.get(u, "?") for u in segment_persons.get(pl["uuid"], ()))
        if names:
            covered += 1
            if pl is plans[0]:
                pl["persons_legacy"] = names
    report["plan_persons"] = {
        "segments_total": len(plans),
        "segments_with_persons": covered,
        "coverage_percent": round(covered / len(plans) * 100, 1),
    }

    report["events"] = {
        "count": len(events_out),
        "with_persons": sum(1 for e in events_out if e["persons"]),
        "persons_in_catalog": len(persons_idx),
        "persons_used_here": len({n for e in events_out for n in e["persons"] if n != "?"}),
        "sample": events_out[:3],
    }

    # --- Персоны ---
    report["persons"] = {
        "in_catalog": len(persons_idx),
        "sample_names": sorted(persons_idx.values())[:8],
    }

    # --- чего нет ---
    report["missing"] = {
        "shot_size": "нет ни в одном файле legacy — считаем автоматически (ADR-0003)",
        "shot_type": "потерян, в legacy были 15 типов в .psd без привязки к сегментам",
        "location": "отсутствует, у нас назначается только вручную",
        "face_boxes": "нет координат лиц: 514 картинок и имена, но без привязки к кадрам",
        "scene_bounds": "у сцен legacy есть только порядок и название, без границ кадров",
        "plan_size": "размер плана в legacy не хранился вовсе",
    }

    (OUT / "import-s1e1-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("=== СЕРИЯ (наш ffprobe) ===")
    for k, v in series.items():
        print(f"  {k}: {v}")
    print(f"  legacy утверждает framesCount: {list(report['series_legacy_says'])}, название: {list(report['series_legacy_says'].values())}")
    print("\n=== 2.8 ПЛАН (из сегментов legacy) ===")
    print(f"  планов: {report['plans_count']}")
    print(f"  непрерывность: пропусков {gaps}, пересечений {overlaps}, "
          f"покрытие {plans[0]['first_frame']}..{plans[-1]['last_frame']}")
    print("\n=== 2.6 КАДР (признаки) ===")
    for k, v in report["frames"].items():
        print(f"  {k}: {v}")
    print("\n=== СЦЕНЫ ===")
    for k, v in report["scenes"].items():
        if k != "sample_names":
            print(f"  {k}: {v}")
    print(f"  примеры названий: {report['scenes']['sample_names']}")
    print("\n=== СОБЫТИЯ (персоны в кадре) ===")
    for k, v in report["events"].items():
        if k != "sample":
            print(f"  {k}: {v}")
    for e in events_out[:3]:
        print(f"    {e['first_frame']}-{e['last_frame']}  {e['name']}  -> {e['persons']}")
    print("\n=== ПРИВЯЗКА ПЕРСОН К ПЛАНАМ (наследие) ===")
    for k, v in report["plan_persons"].items():
        print(f"  {k}: {v}")
    print(f"  связи в legacy: {report['legacy_relations']}")
    print("\n=== ЧЕГО НЕТ ===")
    for k, v in report["missing"].items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
