plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "net.yourname"
version = "1.0.0"

java {
    toolchain {
        // Paper 26.2 is built and compiled against JDK 25.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // "+" resolves to the latest published 26.2 build. Pin to an exact build
    // (e.g. "26.2.build.84-stable") instead if you want reproducible builds.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Paper's logging is backed by Log4j2, but paper-api doesn't expose
    // log4j-core on the compile classpath by itself. Needed for the
    // ConsoleCaptureAppender to compile against Log4j2's core classes.
    // This is compileOnly since the real implementation is provided by the
    // server at runtime — don't shade it into the plugin jar.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")

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
        options.release.set(25)
    }

    processResources {
        filteringCharset = "UTF-8"
    }
}
