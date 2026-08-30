// Только интерфейс и типы данных: ни Bukkit, ни JDBC, ни одной зависимости.
//
// Так и задуман публичный API — companion собирается против него, а реализацию
// в рантайме отдаёт сам AurumGuilds через ServicesManager. Тот же приём, что у
// Vault и у нашей системы авторизации.
dependencies {}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
