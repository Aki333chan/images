// События Bukkit, которые шлёт AurumAuth.
//
// ПОЧЕМУ ОТДЕЛЬНЫМ МОДУЛЕМ, А НЕ В auth-api. Модуль auth-api намеренно пуст на
// зависимости: ни Bukkit, ни JDBC — companion и любой другой плагин собирается
// против него где угодно. Событие же обязано наследоваться от
// org.bukkit.event.Event, то есть тянет за собой paper-api и JDK 25. Смешать
// это в одном модуле значило бы навязать Bukkit тем, кому нужен один
// интерфейс.
//
// Классы этого модуля кладутся внутрь jar плагина (см. paper/build.gradle.kts),
// поэтому подписчику достаточно compileOnly-зависимости: в рантайме класс
// даёт сам AurumAuth.
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
