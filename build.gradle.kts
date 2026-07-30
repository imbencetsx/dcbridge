plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "net.msstore"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // NOTE: adjust this version string to match the exact PaperMC build you run.
    // "1.21.4-R0.1-SNAPSHOT" is used here as a recent stable Paper API.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Discord bot library
    implementation("net.dv8tion:JDA:5.1.2") {
        exclude(module = "opus-java")
    }
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        // Shade JDA + its dependencies into the plugin jar so it doesn't need
        // to be installed separately on the server.
    }

    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        filteringCharset = "UTF-8"
    }
}
