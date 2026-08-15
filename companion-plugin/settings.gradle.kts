rootProject.name = "aurum-companion"

// core — чистая Java без Bukkit: HTTP-сервер, JSON, авторизация, клиент тикетов.
//        Собирается и тестируется где угодно, включая CI без доступа к репозиторию Paper.
// paper — адаптер к Bukkit/Paper API: точка входа плагина, команда /ticket.
//         Требует JDK 25 и репозиторий repo.papermc.io.
include("core", "paper")
