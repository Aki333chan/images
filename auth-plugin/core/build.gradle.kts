// java-library, а не просто java: типы из :api (AuthStatus, PremiumVerdict)
// стоят прямо в сигнатурах публичных методов core, а значит должны быть видны
// тому, кто зависит от core. Это ровно то, для чего существует конфигурация
// api, и её даёт только этот плагин.
plugins {
    `java-library`
}

// Логика без единой строчки Bukkit: конфиг, хранилище, пароли, сессии,
// троттлинг, premium-эвристика. Благодаря этому всё, что легко ломается
// молча, покрывается обычными JUnit-тестами без запуска Minecraft.
dependencies {
    api(project(":auth-api"))

    // bcrypt: at.favre.lib — реализация без Spring Security и прочего груза,
    // с явным API для стоимости хеширования.
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Пул соединений. Новое соединение на каждый запрос — это рукопожатие
    // TCP плюс аутентификация MariaDB на каждый вход игрока.
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Драйвер MariaDB. runtimeOnly: в коде используется только java.sql,
    // драйвер подхватывается по JDBC-URL.
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Байткод 21: плагин заведомо запустится и на Java 25 (Paper 26.x), и на 21.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
