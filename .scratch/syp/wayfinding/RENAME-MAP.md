# Карта переименования (решение владельца 2026-10-03)

Все таблицы получают префикс `tbl_` и имя во множественном числе.
Сущности в коде: `serial` → `Movie`, `series` → `Episode`.

| Было | Стало | Сущность |
|---|---|---|
| serial | tbl_movies | Movie |
| series | tbl_episodes | Episode |
| analysis_run | tbl_analysis_runs | AnalysisRun |
| analysis_setting | tbl_analysis_settings | AnalysisSetting |
| artifact | tbl_artifacts | Artifact |
| build_recipe | tbl_build_recipes | BuildRecipe |
| build_recipe_item | tbl_build_recipe_items | BuildRecipeItem |
| face | tbl_faces | Face |
| face_embedding | tbl_face_embeddings | FaceEmbedding |
| filter_condition | tbl_filter_conditions | FilterCondition |
| filter_group | tbl_filter_groups | FilterGroup |
| frame | tbl_frames | Frame |
| job | tbl_jobs | Job |
| location | tbl_locations | Location |
| model_version | tbl_model_versions | ModelVersion |
| model_version_example | tbl_model_version_examples | ModelVersionExample |
| person | tbl_persons | Person |
| raw_boundary | tbl_raw_boundaries | RawBoundary |
| scene | tbl_scenes | Scene |
| season | tbl_seasons | Season |
| shot | tbl_shots | Shot |
| source_file_checksum | tbl_source_file_checksums | SourceFileChecksum |
| syp_filter | tbl_filters | Filter |

Столбцы-ссылки: `serial_id` → `id_movie`, `series_id` → `id_episode` — во всех
таблицах, где они встречаются (15 мест).
