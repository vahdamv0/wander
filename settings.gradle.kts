rootProject.name = "wander"

pluginManagement {
    plugins {
        id("org.springframework.boot") version "4.1.1"
        id("com.github.node-gradle.node") version "7.1.0"
    }
}

include("api", "web")
