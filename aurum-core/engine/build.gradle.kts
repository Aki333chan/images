plugins { `java-library` }

dependencies {
    api(project(":aurum-api"))
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.google.code.gson:gson:2.13.2")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
