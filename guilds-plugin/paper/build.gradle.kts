// Адаптер к Paper API.
//
// ВНИМАНИЕ: этот модуль требует JDK 25 — Paper 26.x скомпилирован под него.
// Модули guilds-api и core собираются любым JDK >= 21, поэтому логику гильдий
// можно гонять тестами и без JDK 25.
//
// ВСЕ ЧЕТЫРЕ ИНТЕГРАЦИИ ЗДЕСЬ compileOnly, И ЭТО ГЛАВНОЕ АРХИТЕКТУРНОЕ РЕШЕНИЕ
// ПЛАГИНА. LuckPerms, Vault, AurumAuth и WorldGuard не обязаны стоять на
// сервере. Классы
// каждого из них собраны в отдельном файле-мосте, который загружается только
// после проверки «а есть ли такой плагин» через PluginManager. Нет плагина —
// класс моста ни разу не трогают, NoClassDefFoundError не возникает, а
// соответствующая часть возможностей просто выключена.
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    // WorldGuard и WorldEdit публикуются в своём репозитории EngineHub.
    // Публичный, токен не нужен.
    maven {
        name = "enginehub"
        url = uri("https://maven.enginehub.org/repo/")
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

    // WorldGuard: только его API, ради привязки региона к гильдии.
    // compileOnly и мягко — см. softdepend в plugin.yml. Без WorldGuard
    // команда /guild claim честно отвечает, что привязывать не к чему, а всё
    // остальное в гильдиях работает.
    //
    // WorldEdit тянется транзитивно и нужен по делу: адрес мира в WorldGuard —
    // это тип WorldEdit (BukkitAdapter.adapt), без него не обратиться к
    // менеджеру регионов.
    // isTransitive = false ОБЯЗАТЕЛЬНО, и вот почему. В метаданных WorldGuard
    // и WorldEdit прописаны strictly-версии Guava, Gson и fastutil с пометкой
    // «Mojang provides Guava»: они рассчитывают, что рядом старый сервер.
    // Paper 26 приносит те же библиотеки новее, strictly-ограничение с ними не
    // сходится, и сборка падает на «Cannot find a version that satisfies the
    // version constraints» — причём в сообщении ни слова о том, что виноват
    // WorldGuard.
    //
    // Нам от них нужны только собственные классы, а Guava и Gson всё равно
    // придут из paper-api. Поэтому берём по одному jar без хвоста.
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.13") { isTransitive = false }
    // BukkitAdapter живёт в WorldEdit, а не в WorldGuard: адрес мира для
    // менеджера регионов — тип WorldEdit, и без этой пары не обратиться.
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }
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
