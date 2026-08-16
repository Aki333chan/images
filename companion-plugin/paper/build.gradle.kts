// Адаптер к Paper API.
//
// ВНИМАНИЕ: этот модуль требует JDK 25 — Paper 26.x скомпилирован под него,
// и javac более старой версии не прочитает его class-файлы. Модуль core
// собирается любым JDK >= 21, поэтому тесты логики можно гонять и без JDK 25.
repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(project(":core"))
    // Актуальная схема версий Paper (с 26.1 суффикс -R0.1-SNAPSHOT больше не используется):
    // {ВЕРСИЯ}.build.+ — последний билд ветки.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // LuckPerms: только его Developer API, с Maven Central. compileOnly —
    // классы предоставляет сам LuckPerms в рантайме, внутрь нашего jar они
    // не попадают. Зависимость мягкая: см. softdepend в plugin.yml.
    compileOnly("net.luckperms:api:5.5")

    // InvSee++ compileOnly-зависимостью НЕ подключается намеренно: его
    // артефакт лежит в GitHub Packages, требующем токен даже для публичных
    // пакетов, и сборка плагина стала бы невозможна без учётки GitHub.
    // Вызовы идут рефлексией — см. InvSeeIntegration.
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

// Классы core кладём внутрь того же jar — плагину не нужны внешние зависимости.
tasks.jar {
    archiveBaseName.set("AurumCompanion")
    from(project(":core").sourceSets["main"].output)
}
