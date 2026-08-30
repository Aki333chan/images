// java-library, а не просто java: типы из :guilds-api стоят прямо в сигнатурах
// публичных методов core, а значит должны быть видны тому, кто зависит от core.
plugins {
    `java-library`
}

// Логика без единой строчки Bukkit: конфиг, хранилище, гильдии, пати, разбор
// имён и тегов, содержимое сайдбара. Благодаря этому всё, что легко ломается
// молча, покрывается обычными JUnit-тестами без запуска Minecraft.
dependencies {
    api(project(":guilds-api"))

    // Пул соединений. Новое соединение на каждый запрос — это рукопожатие TCP
    // плюс аутентификация MariaDB на каждую операцию с гильдией.
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
