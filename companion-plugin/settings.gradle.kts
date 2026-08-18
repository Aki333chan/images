// Резолвер тулчейнов: позволяет Gradle самому скачать нужный JDK, если его
// нет в системе. Без него сборка модуля paper падает с
// «Cannot find a Java installation … matching {languageVersion=25}» на любой
// машине, где JDK 25 не установлен вручную.
//
// Если JDK 25 в системе уже есть, Gradle берёт его и в сеть за ним не ходит.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "aurum-companion"

// core — чистая Java без Bukkit: HTTP-сервер, JSON, авторизация, клиент тикетов.
//        Собирается и тестируется где угодно, включая CI без доступа к репозиторию Paper.
// paper — адаптер к Bukkit/Paper API: точка входа плагина, команда /ticket.
//         Требует JDK 25 — Paper 26.x скомпилирован под него.
include("core", "paper")
