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
