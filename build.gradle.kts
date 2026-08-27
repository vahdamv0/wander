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
