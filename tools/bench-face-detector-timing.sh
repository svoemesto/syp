#!/usr/bin/env bash
# SYP — повторные прогоны для скорости (задача Т076, замер М-02).
#
# Один прогон — не измерение: на машине карта занята посторонним процессом,
# и разброс между прогонами может быть больше разницы между моделями.
# Поэтому каждая модель прогоняется три раза подряд, в отчёт идёт среднее и
# разброс. Модели идут строго по очереди: параллельный замер на одной карте
# сравнивал бы не модели, а очередь к видеокарте.
set -uo pipefail

MODELS_DIR=/tmp/t076/models
FRAMES=/tmp/t076/frames-1080p.raw
OUT=/tmp/t076/timing
SERIES=/sources/GOT.S01/GOT.S01E01.BDRip.1080p.mkv
BENCH=/tmp/t076/bench-face-detector.py
THRESHOLD=0.30

mkdir -p "${OUT}"

timing() {
    local kind="$1" model="$2" tag="$3"
    echo "=== ${tag}, порог ${THRESHOLD}, три прогона"
    python3 "${BENCH}" \
        --model "${MODELS_DIR}/${model}" \
        --model-kind "${kind}" \
        --series "${SERIES}" \
        --frames-file "${FRAMES}" \
        --score-threshold "${THRESHOLD}" \
        --nms-threshold 0.4 \
        --runs 3 \
        --out "${OUT}/${tag}.json" 2>&1 | sed 's/^/    /'
}

timing yunet     face_detection_yunet_2023mar.onnx yunet-2023mar
timing yunet     face_detection_yunet_2026may.onnx yunet-2026may
timing scrfd     det_10g.onnx                     scrfd-10g
timing scrfd     scrfd_person_2.5g.onnx           scrfd-p2.5g
timing retinaface retinaface.onnx                  retinaface
echo "замеры скорости закончены"
