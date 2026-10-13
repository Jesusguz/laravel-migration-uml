plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "schematouml"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.2.5")
    type.set("PS")
    plugins.set(listOf("com.jetbrains.php"))
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("271.*")
    }
}

kotlin {
    jvmToolchain(17)
}