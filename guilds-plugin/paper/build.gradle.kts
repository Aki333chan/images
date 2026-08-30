// Адаптер к Paper API.
//
// ВНИМАНИЕ: этот модуль требует JDK 25 — Paper 26.x скомпилирован под него.
// Модули guilds-api и core собираются любым JDK >= 21, поэтому логику гильдий
// можно гонять тестами и без JDK 25.
//
// ВСЕ ТРИ ИНТЕГРАЦИИ ЗДЕСЬ compileOnly, И ЭТО ГЛАВНОЕ АРХИТЕКТУРНОЕ РЕШЕНИЕ
// ПЛАГИНА. LuckPerms, Vault и AurumAuth не обязаны стоять на сервере. Классы
// каждого из них собраны в отдельном файле-мосте, который загружается только
// после проверки «а есть ли такой плагин» через PluginManager. Нет плагина —
// класс моста ни разу не трогают, NoClassDefFoundError не возникает, а
// соответствующая часть возможностей просто выключена.
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    // VaultAPI публикуется через JitPack; репозиторий публичный, токен не нужен.
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    implementation(project(":core"))
    // guilds-api тянется транзитивно через core (там он объявлен как api),
    // но объявляем явно: этот модуль реализует интерфейс напрямую.
    implementation(project(":guilds-api"))

    // Схема версий Paper (с 26.1 суффикс -R0.1-SNAPSHOT не используется):
    // {ВЕРСИЯ}.build.+ — последний билд ветки.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // LuckPerms: только его Developer API. Нужен для группы гильдии и суффикса.
    compileOnly("net.luckperms:api:5.5")

    // Vault: только его API, ради банка гильдии. Исключение org.bukkit:bukkit
    // обязательно — иначе Gradle видит двух поставщиков одной и той же
    // способности (paper-api объявляет capability org.bukkit:bukkit, а
    // пересобранный JitPack-ом pom VaultAPI тянет настоящий bukkit 1.13) и
    // отказывается выбирать. Подробный разбор — в companion-plugin.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // События нашей системы авторизации: PlayerAccountDeletedEvent.
    // Разрешается из составной сборки, см. settings.gradle.kts.
    compileOnly("ovh.aurumgg:auth-events:0.1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
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

// Классы core, guilds-api и их зависимости (HikariCP, драйвер MariaDB) кладём
// внутрь jar: плагин ставится копированием одного файла в plugins/.
tasks.jar {
    archiveBaseName.set("AurumGuilds")
    from(project(":core").sourceSets["main"].output)
    from(project(":guilds-api").sourceSets["main"].output)
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
