import com.github.gradle.node.npm.task.NpmTask

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

// Angular writes to build/frontend/browser (see angular.json outputPath), which
// keeps generated output inside Gradle's build dir so `clean` reaches it.
tasks.named<Jar>("jar") {
    dependsOn(ngBuild)
    from(layout.buildDirectory.dir("frontend/browser")) {
        into("META-INF/resources")
    }
}
