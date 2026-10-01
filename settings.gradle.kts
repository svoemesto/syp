// SYP — корневая сборка Gradle.
//
// Модулей ровно три: общий `syp-core` и два бэкенда. Фронтенды и воркер
// пользователя в сборку града не входят: у фронтендов свой конвейер npm,
// а воркер — программа на Python 3, исполняемая на машине пользователя
// (ADR-0011, constitution V).
rootProject.name = "syp"

include("syp-core")
include("syp-admin-app")
include("syp-public-app")
