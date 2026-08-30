// Адаптер к Paper API.
//
// ВНИМАНИЕ: этот модуль требует JDK 25 — Paper 26.x скомпилирован под него.
// Модули api и core собираются любым JDK >= 21, поэтому логику входа можно
// гонять тестами и без JDK 25 (и без доступа к репозиторию Paper).
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":core"))
    // api тянется транзитивно через core (там он объявлен как api),
    // но объявляем явно: этот модуль реализует интерфейс напрямую.
    implementation(project(":auth-api"))
    // События плагина. Отдельным модулем — см. auth-events/build.gradle.kts.
    implementation(project(":auth-events"))

    // Схема версий Paper (с 26.1 суффикс -R0.1-SNAPSHOT не используется):
    // {ВЕРСИЯ}.build.+ — последний билд ветки.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// Классы core, api и их зависимости (bcrypt, HikariCP, драйвер MariaDB) кладём
// внутрь jar: плагин ставится копированием одного файла в plugins/, доставать
// зависимости руками администратору не придётся.
tasks.jar {
    archiveBaseName.set("AurumAuth")
    from(project(":core").sourceSets["main"].output)
    from(project(":auth-api").sourceSets["main"].output)
    from(project(":auth-events").sourceSets["main"].output)
    from({
        project(":core").configurations["runtimeClasspath"]
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        // Подписи чужих jar внутри нашего — верный способ получить
        // SecurityException при загрузке классов.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
