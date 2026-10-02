#!/usr/bin/env bash
# SYP — подготовка контейнера админки к замеру М-02 (задача T076).
#
# Скрипт идемпотентен: на машине стек пересоздают и извне, содержимое /tmp
# контейнера при этом пропадает. Повторный запуск возвращает стенд, модели и
# выборку кадров на место, поэтому замер не теряется из-за чужой
# пересоздавалки.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA=/home/nsa/syp/.data/t076
CONTAINER=syp-admin-app

docker exec "${CONTAINER}" mkdir -p /tmp/t076/models
docker cp "${ROOT}/syp-admin-app/src/main/resources/detector/syp_face_detector.py" \
    "${CONTAINER}:/opt/syp/face-detector/bin/syp-face-detector"
for name in face_detection_yunet_2023mar.onnx face_detection_yunet_2026may.onnx \
            det_10g.onnx scrfd_person_2.5g.onnx retinaface.onnx; do
    if [ ! -f "${CONTAINER}:/tmp/t076/models/${name}" ]; then
        docker cp "${DATA}/${name}" "${CONTAINER}:/tmp/t076/models/${name}"
    fi
done
docker cp "${ROOT}/tools/bench-face-detector.py" "${CONTAINER}:/tmp/t076/bench-face-detector.py"
docker cp "${ROOT}/tools/bench-face-detector-sweep.sh" "${CONTAINER}:/tmp/t076/sweep.sh"
if [ ! -f "${CONTAINER}:/tmp/t076/frames-1080p.raw" ]; then
    docker cp "${DATA}/frames-1080p.raw" "${CONTAINER}:/tmp/t076/frames-1080p.raw"
fi
docker exec -u root "${CONTAINER}" sh -c \
    'chmod 0755 /tmp/t076/bench-face-detector.py /tmp/t076/sweep.sh /opt/syp/face-detector/bin/syp-face-detector; chmod 0644 /tmp/t076/frames-1080p.raw /tmp/t076/models/*.onnx; mkdir -p /tmp/t076/results; chown -R syp:syp /tmp/t076'
docker exec "${CONTAINER}" python3 -c "print('стенд на месте')"
