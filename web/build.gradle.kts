import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.task.NodeTask

plugins {
    java
    id("com.github.node-gradle.node")
}

val skipFrontend = (project.findProperty("frontend.skip") as String?).toBoolean()

node {
    // Pinned so every contributor and CI runner builds with the same toolchain,
    // whatever node happens to be on PATH. Angular 22's CLI refuses anything
    // below 22.22.3, which is newer than many distro packages.
    version = "24.20.0"
    download = true
    nodeProjectDir = layout.projectDirectory
}

// npm ci, not npm install: the lockfile decides, and a drifting lockfile fails
// the build instead of being silently rewritten.
tasks.named("npmInstall") {
    enabled = !skipFrontend
}

val ngBuild = tasks.register<NpmTask>("ngBuild") {
    dependsOn(tasks.named("npmInstall"))
    enabled = !skipFrontend
    npmCommand = listOf("run", "build")
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("package.json", "package-lock.json", "angular.json", "tsconfig.json", "tsconfig.app.json")
    outputs.dir(layout.buildDirectory.dir("frontend"))
}

// Regenerates web/src/app/api from api/build/openapi.json, which :api:test writes.
//
// Through the node plugin rather than a bare `npx`, so it uses the pinned Node
// downloaded above instead of whatever is on PATH. Both places that matter have
// no usable one: the CI image is a JDK with no node or npx at all, and a
// developer machine may well be below Angular's 22.22.3 floor. `npm run api:gen`
// still works wherever the local toolchain is new enough.
tasks.register<NpmTask>("apiGen") {
    description = "Regenerates web/src/app/api from api/build/openapi.json (run :api:test first)."
    dependsOn(tasks.named("npmInstall"))
    npmCommand = listOf("run", "api:gen")
    inputs.file(rootProject.file("api/build/openapi.json"))
    outputs.dir("src/app/api")
}

tasks.register<NodeTask>("webLicenses") {
    description = "Writes third-party notices for the shipped browser bundle."
    dependsOn(tasks.named("npmInstall"))
    script = layout.projectDirectory.file("scripts/collect-licenses.mjs").asFile
    val out = layout.buildDirectory.file("third-party/THIRD-PARTY-web.txt")
    args = listOf(out.get().asFile.absolutePath)
    inputs.files("package.json", "package-lock.json", "scripts/collect-licenses.mjs")
    outputs.file(out)
}

// Angular writes to build/frontend/browser (see angular.json outputPath), which
// keeps generated output inside Gradle's build dir so `clean` reaches it.
tasks.named<Jar>("jar") {
    dependsOn(ngBuild)
    from(layout.buildDirectory.dir("frontend/browser")) {
        into("META-INF/resources")
    }
}
