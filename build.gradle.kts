plugins {
    java
    id("org.springframework.boot") apply false
}

allprojects {
    group = "com.wander"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

// The root project is a container only.
tasks.named("jar") { enabled = false }

/*
 * One THIRD-PARTY.txt for the whole distribution.
 *
 * Two halves, because there are two dependency trees and neither tool can see
 * the other's: `:web:webLicenses` walks node_modules for what ships in the
 * browser bundle, `:api:generateLicenseReport` reads POMs for what ships in
 * BOOT-INF/lib. Both end up inside the same boot jar, so both are distributed
 * and both owe notices.
 *
 * Written to build/THIRD-PARTY.txt and copied into the image by the Dockerfile.
 * It is generated rather than committed for the reason every generated file in
 * this project is: a hand-maintained copy goes stale the first time somebody
 * adds a dependency and does not think about licences — which is the normal
 * case, and exactly when the file most needs to be right.
 */
tasks.register("thirdPartyNotices") {
    group = "distribution"
    description = "Writes build/THIRD-PARTY.txt: notices for everything shipped in the boot jar."

    val web = project(":web").tasks.named("webLicenses")
    val java = project(":api").tasks.named("generateLicenseReport")
    dependsOn(web, java)

    val webFile = project(":web").layout.buildDirectory.file("third-party/THIRD-PARTY-web.txt")
    val javaFile = project(":api").layout.buildDirectory.file("reports/licenses/THIRD-PARTY-java.txt")
    val outFile = layout.buildDirectory.file("THIRD-PARTY.txt")

    inputs.files(webFile, javaFile)
    outputs.file(outFile)

    doLast {
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("wander — third-party notices")
                appendLine()
                appendLine("wander itself is AGPL-3.0-or-later; see LICENSE. This file covers the")
                appendLine("third-party code redistributed inside the boot jar, and reproduces the")
                appendLine("copyright notices that MIT, BSD, ISC and Apache-2.0 require to travel with")
                appendLine("a binary distribution.")
                appendLine()
                appendLine("Two sections, because there are two dependency trees: the browser bundle")
                appendLine("under META-INF/resources, and the server's jars under BOOT-INF/lib.")
                appendLine("Build-time tooling appears in neither — it is not distributed.")
                appendLine()
                appendLine("=".repeat(78))
                appendLine("PART 1 — BROWSER BUNDLE")
                appendLine("=".repeat(78))
                appendLine()
                append(webFile.get().asFile.readText())
                appendLine()
                appendLine("=".repeat(78))
                appendLine("PART 2 — SERVER (BOOT-INF/lib)")
                appendLine("=".repeat(78))
                appendLine()
                append(javaFile.get().asFile.readText())
            },
        )
        logger.lifecycle("third-party notices -> ${out.absolutePath} (${out.length() / 1024} KB)")
    }
}
