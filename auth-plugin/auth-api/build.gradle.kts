// Только интерфейсы: ни Bukkit, ни JDBC, ни одной зависимости.
//
// Так и задуман публичный API — companion (и любой другой плагин) собирается
// против него, а реализацию в рантайме отдаёт сам AurumAuth через
// ServicesManager. Тот же приём, что у Vault: VaultAPI — отдельный маленький
// артефакт, а деньги ведёт совсем другой плагин.
dependencies {}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
