rootProject.name = "wander"

pluginManagement {
    plugins {
        id("org.springframework.boot") version "4.1.1"
        id("com.github.node-gradle.node") version "7.1.0"
        id("com.github.jk1.dependency-license-report") version "2.9"
    }
}

include("api", "web")
