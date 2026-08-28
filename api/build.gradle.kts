plugins {
    java
    id("org.springframework.boot")
}

val springBootVersion = "4.1.1"
val springdocVersion = "3.1.0"
// Boot 4's BOM no longer manages Testcontainers, so its own BOM decides.
val testcontainersVersion = "2.0.5"

dependencies {
    // Boot's BOM directly, rather than the io.spring.dependency-management
    // plugin — that plugin predates Gradle's own platform support and drags
    // deprecated APIs along with it.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    // Live sync. A plain WebSocket handler, not STOMP: the client sends nothing
    // and the payload is one small invalidation event, so a broker and a
    // sub-protocol would be machinery with nothing to carry.
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    runtimeOnly("org.postgresql:postgresql")
    // Boot 4 split every integration into its own module: flyway-core alone
    // gets you the library with no autoconfiguration, so migrations never run
    // and Hibernate then fails validation against an empty schema.
    implementation("org.springframework.boot:spring-boot-flyway")
    // Flyway 10+ ships each database's support separately.
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // The built Angular app, packaged as META-INF/resources so Boot serves it
    // straight out of the jar. One artifact to deploy, no external web server.
    implementation(project(":web"))

    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers 2.x renamed these from junit-jupiter / postgresql.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<JavaCompile>().configureEach {
    // -parameters keeps constructor parameter names for Spring's binding.
    options.compilerArgs.add("-parameters")
    // Deprecations are shown in full rather than as a one-line note, so a
    // library repackaging something is visible the first time it happens.
    options.compilerArgs.add("-Xlint:deprecation")
}
