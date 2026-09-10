repositories {
    maven { name = "papermc"; url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { name = "jitpack"; url = uri("https://jitpack.io") }
    maven { name = "placeholderapi"; url = uri("https://repo.extendedclip.com/releases/") }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":aurum-api"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("me.clip:placeholderapi:2.12.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.yaml:snakeyaml:2.3")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("AurumCore")
    from(project(":engine").sourceSets["main"].output)
    from(project(":aurum-api").sourceSets["main"].output)
    from({
        project(":engine").configurations["runtimeClasspath"]
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
