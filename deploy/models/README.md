# Модели детектора лиц

Каталог `deploy/models/` хранит **файлы весов** моделей детектора лиц. Сами
файлы в git не попадают: `deploy/models/*.onnx` перечислены в `.gitignore`.
Лицензии моделей рядом с ними **коммитятся** — лицензия обязана быть в
репозитории, вес модели — нет.

## Почему вес модели не в репозитории

Вес — это артефакт внешнего проекта, а не код SYP. Он меняется без изменения
нашего кода, весит от 229 КБ до 118 МБ и не проходит ревью как текст.
Инструкция по скачиванию обязана позволять воспроизвести модель на другой
машине — для этого здесь указаны точный адрес, размер и контрольная сумма.

## Как скачать

```bash
cd deploy/models
# YuNet, 2023 год, OpenCV Zoo
curl -sSL -o face_detection_yunet_2023mar.onnx \
  https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
# YuNet, 2026 год, OpenCV Zoo
curl -sSL -o face_detection_yunet_2026may.onnx \
  https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2026may.onnx
# SCRFD 2.5G, InsightFace (официальный релиз модели)
curl -sSL -o scrfd_person_2.5g.onnx \
  https://github.com/deepinsight/insightface/releases/download/model-zoo/scrfd_person_2.5g.onnx
# RetinaFace, веса DeepFace (репозиторий весов)
curl -sSL -o retinaface.onnx \
  https://github.com/serengil/deepface_models/releases/download/v1.0/retinaface.onnx
```

Адреса приведены как есть; при изменении они могут перестать работать, и это
повод обновить таблицу ниже, а не молча скачать что-то другое.

## Файлы, контрольные суммы и происхождение

| Файл | Байт | sha256 | Откуда |
|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | 232 589 | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | `opencv/opencv_zoo`, каталог `models/face_detection_yunet` |
| `face_detection_yunet_2026may.onnx` | 229 738 | `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0` | тот же каталог, вес модели 2026 года |
| `scrfd_person_2.5g.onnx` | 3 710 223 | `76522ba15eecb0712780509e912884aba066e9834be0c85761918cdcf76de5b5` | `deepinsight/insightface`, релиз `model-zoo` |
| `retinaface.onnx` | 117 888 450 | `45d02defc4737b382691a52fc2bb49b984420dcbd26ee6ab9a8343c667d228f7` | `serengil/deepface_models`, релиз `v1.0` |

## Лицензии

Файлы лицензий лежат в этом же каталоге. Каждый скачан из репозитория самой
модели, а не воспроизведён по памяти.

| Модель | Файл лицензии | Что в нём написано |
|---|---|---|
| YuNet | `LICENSE-face_detection_yunet.txt` | MIT, OpenCV Zoo |
| SCRFD | код — `LICENSE-insightface-code.md` (в репозитории нет отдельного файла лицензии, текст взят из раздела `License` файла `README.md`); **веса — `MODEL-LICENSE-insightface.md`** | Код InsightFace — MIT без ограничений. **Обучающие данные и обученные на них модели — только для некоммерческих исследований.** Цитата из `README.md`, раздел `License`: «The training data containing the annotation (and the models trained with these data) are available for non-commercial research purposes only» |
| RetinaFace | `LICENSE-retinaface-serengil.txt`; `README-deepface_models.md` | Репозиторий весов DeepFace — MIT. Сам README предупреждает: «Licence types will be inherited», то есть лицензия исходной модели наследуется |
| MTCNN (не измерялась) | `LICENSE-mtcnn-ipazc.txt`, `LICENSE-facenet-pytorch.txt` | MIT у обоих исходных проектов |
| YOLO-face (не измерялся) | `LICENSE-yolov8-face.txt` — GPL-3.0; `LICENSE-ultralytics.txt` — **AGPL-3.0** | Выпуск в продакшен потребует отдельного решения владельца |
| MediaPipe (не измерялся) | `LICENSE-mediapipe.txt` — Apache-2.0 | Лицензия разрешает, но ONNX-весов в репозитории нет |

**Вывод по лицензиям (требует внимания владельца).** Код InsightFace — MIT, но
его веса, включая SCRFD, распространяются **только для некоммерческих
исследований**. Для проекта, который собирает подборки видео, это ограничение
существенно; решение о коммерческом использовании — за владельцем.
