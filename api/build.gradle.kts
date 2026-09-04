import com.github.jk1.license.render.TextReportRenderer
import com.github.jk1.license.filter.LicenseBundleNormalizer

plugins {
    java
    id("org.springframework.boot")
    id("com.github.jk1.dependency-license-report")
}

/*
 * Third-party notices for everything that ships inside the boot jar's
 * BOOT-INF/lib. MIT, BSD and ISC all require the copyright notice to travel
 * with a *binary* distribution, and a container image is a binary distribution
 * — so a jar with no notices is the one licence obligation this project was
 * failing while insisting on its own (the AGPL section 13 source link).
 *
 * runtimeClasspath, not compileClasspath and not testRuntimeClasspath: the
 * question is what gets distributed. A test-only dependency is never shipped,
 * so it imposes nothing, exactly as the web build's `--omit=dev` argues.
 */
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf(TextReportRenderer("THIRD-PARTY-java.txt"))
    // POMs spell the same licence a dozen ways ("Apache 2", "The Apache
    // Software License, Version 2.0"). The normaliser maps them onto SPDX ids
    // so the report can be read, and so a policy can be written against it.
    filters = arrayOf(LicenseBundleNormalizer())
    outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.absolutePath
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

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Self-service password reset delivers a link by mail, and this is the only
    // outbound dependency an *operator* has to configure — everything else
    // upstream (Nominatim, Open-Meteo, the tiles) has a working public default.
    // Off unless wander.mail.enabled, so an instance with no SMTP relay, or no
    // outbound network at all, is unaffected. The starter brings the
    // autoconfiguration plus angus-mail, which is the implementation behind
    // jakarta.mail-api: the API alone would compile and then fail at runtime.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    // Live sync. A plain WebSocket handler, not STOMP: the client sends nothing
    // and the payload is one small invalidation event, so a broker and a
    // sub-protocol would be machinery with nothing to carry.
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // Sessions in Postgres rather than in Tomcat's heap, so a restart does not
    // sign everybody out. Flyway owns the two tables; see V5.
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    // Boot 4 split every integration into its own module: flyway-core alone
    // gets you the library with no autoconfiguration, so migrations never run
    // and Hibernate then fails validation against an empty schema.
    implementation("org.springframework.boot:spring-boot-flyway")
    // The built Angular app, packaged as META-INF/resources so Boot serves it
    // straight out of the jar. One artifact to deploy, no external web server.
    implementation(project(":web"))

    runtimeOnly("org.postgresql:postgresql")
    // Flyway 10+ ships each database's support separately.
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

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
