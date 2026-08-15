// Модуль без единой зависимости на Bukkit/Paper — всё общение с игрой идёт
// через интерфейс GameBridge. Благодаря этому HTTP-слой, JSON, проверка токена
// и клиент тикетов покрываются обычными JUnit-тестами без запуска сервера.
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Байткод 21: плагин заведомо запустится и на Java 25 (Paper 26.x), и на 21.
    // Toolchain намеренно не задаём — сборка идёт тем JDK, которым запущен Gradle.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
