plugins {
    id("org.jetbrains.intellij.platform") version "2.0.0"
    kotlin("jvm") version "1.9.22"
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.13.2")

    intellijPlatform {
        androidStudio("2023.1.1.28")

        // Dart is a Marketplace plugin (not bundled in Android Studio).
        // 231.9411 is compatible with platform build 231 (AS 2023.1.1.28).
        plugin("Dart", "231.9411")

        instrumentationTools()
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks {
    test {
        systemProperty("healer.test.fixture.classpath", sourceSets.test.get().output.classesDirs.asPath)
    }

    buildSearchableOptions {
        enabled = false
    }
}

kotlin {
    jvmToolchain(17)
}