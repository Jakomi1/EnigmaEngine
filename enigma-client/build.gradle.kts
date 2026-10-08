plugins {
    id("java")
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    implementation("com.terraformersmc:modmenu:20.0.3")
    // Embedded EnigmaEngine code (EnigmaDistributedConfig) - compile-time only:
    // the yaml loader path is never called by the mod, so snakeyaml is not shipped.
    compileOnly("org.yaml:snakeyaml:2.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}
