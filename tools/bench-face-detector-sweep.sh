#!/usr/bin/env bash
# SYP — прогон стенда замера по всем моделям и порогам (задача T076).
#
# Скрипт выполняется внутри контейнера syp-admin-app: он последовательно
# запускает стенд для каждой модели и каждого порога уверенности. Модели идут
# строго по очереди, а не параллельно: замер скорости на загруженной карте
# искажался бы ещё сильнее, а смысл замера именно в сравнении.
set -uo pipefail

MODELS_DIR=/tmp/t076/models
FRAMES=/tmp/t076/frames-1080p.raw
OUT=/tmp/t076/results
SERIES=/sources/GOT.S01/GOT.S01E01.BDRip.1080p.mkv
BENCH=/tmp/t076/bench-face-detector.py

mkdir -p "${OUT}"

run_one() {
    local kind="$1" model="$2" tag="$3" threshold="$4" runs="$5"
    echo "=== ${tag} порог ${threshold}, прогонов ${runs}"
    python3 "${BENCH}" \
        --model "${MODELS_DIR}/${model}" \
        --model-kind "${kind}" \
        --series "${SERIES}" \
        --frames-file "${FRAMES}" \
        --score-threshold "${threshold}" \
        --nms-threshold 0.4 \
        --runs "${runs}" \
        --out "${OUT}/${tag}-t${threshold}.json" 2>&1 | sed 's/^/    /'
}

# Шаг 1: развёртка по порогам, по одному прогону. Все пороги считаются на
# полной выборке в 996 кадров, из неё же берётся размеченная сотня.
for threshold in 0.05 0.2 0.4 0.6 0.8 0.9; do
    run_one yunet     face_detection_yunet_2023mar.onnx yunet-2023mar "$threshold" 1
    run_one yunet     face_detection_yunet_2026may.onnx yunet-2026may "$threshold" 1
    run_one scrfd     det_10g.onnx                     scrfd-10g     "$threshold" 1
    run_one scrfd     scrfd_person_2.5g.onnx           scrfd-p2.5g   "$threshold" 1
    run_one retinaface retinaface.onnx                  retinaface    "$threshold" 1
done
echo "развёртка по порогам закончена"
