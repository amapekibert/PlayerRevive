plugins {
    kotlin("jvm") version "2.0.0"
    // Новий плагін Shadow, який підтримує Java 21
    id("com.gradleup.shadow") version "8.3.0"
}

group = "org.titago"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Твоя версія, як ти і просив
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    shadowJar {
        // Переміщуємо бібліотеки, щоб не було конфліктів
        relocate("kotlin", "org.titago.playerrevive.lib.kotlin")
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }
}