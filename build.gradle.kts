import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.core.tasks.patchroulette.AbstractPatchRouletteTask


import java.util.Properties

plugins {
    java
    id("io.canvasmc.weaver.patcher") version "2.4.5"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1" apply false
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val canvasMavenPublicUrl = "https://maven.canvasmc.io/public/"

paperweight {
    filterPatches = false
    gitFilePatches = false
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("canvas-server/build.gradle.kts")
            patchFile = file("canvas-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("canvas-api/build.gradle.kts")
            patchFile = file("canvas-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("canvas-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(canvasMavenPublicUrl)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    tasks.withType<AbstractPatchRouletteTask>().configureEach {
        endpoint = "https://patch-roulette.canvasmc.io/api"
    }

    if (project.name == "canvas-server") {
        tasks.named("jar") {
            mustRunAfter(rootProject.tasks.named("applyAllPatches"))
        }
        tasks.named("processResources") {
            dependsOn("applyMinecraftResourcePatches")
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://maven.canvasmc.io/releases") {
                name = "canvasmc"
                credentials {
                    username = providers.environmentVariable("PUBLISH_USER").orNull
                    password = providers.environmentVariable("PUBLISH_TOKEN").orNull
                }
            }
        }
    }

    if (project.name.endsWith("-debug") || project.name.endsWith("-plugin")) {
        apply(plugin = "xyz.jpenilla.resource-factory-paper-convention")
        dependencies {
            compileOnly(rootProject.projects.canvasServer) {
                targetConfiguration = JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
            }
        }
        extensions.configure<xyz.jpenilla.resourcefactory.paper.PaperPluginYaml> {
            apiVersion.set(providers.gradleProperty("apiVersion"))
            version = "SNAPSHOT-DEV"
            authors = listOf("CanvasMC")
            foliaSupported = true
        }

        tasks.processResources {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

// patching scripts
tasks.register("fixupMinecraftFilePatches") {
    dependsOn(":canvas-server:fixupMinecraftSourcePatches")
}

val serverJarDir = layout.projectDirectory.dir("serverJar")

// Keep the build artifact at the stable filename used by the local server run folder. A branch
// suffix here made `buildEnigmaEngine` write a different file while the server continued to start
// an older `EnigmaEngine.jar`.
val enigmaEngineJarName = providers.provider { "EnigmaEngine.jar" }

val enigmaClientVersion: String = Properties().apply {
    file("enigma-client/gradle.properties").inputStream().use { load(it) }
}.getProperty("mod_version", "1.0.0")

val enigmaClientJarName = "EnigmaClient-$enigmaClientVersion.jar"

tasks.register<CopyPaperclipJar>("buildEnigmaEngine") {
    description = "Baut die Server-Jar mit allen aktuellen Patches nach serverJar/EnigmaEngine.jar"
    group = "build"

    dependsOn(":canvas-server:createPaperclipJar")

    val outDir = serverJarDir
    libsDir.set(file("canvas-server/build/libs"))
    outputFile.set(enigmaEngineJarName.map { name -> outDir.file(name) })
}

tasks.register<Exec>("buildEnigmaClient") {
    description = "Baut die EnigmaClient-Fabric-Mod und legt sie als EnigmaClient-<version>.jar in serverJar/ ab"
    group = "build"

    val clientDir = layout.projectDirectory.dir("enigma-client")
    val clientBuildLibs = clientDir.dir("build/libs")
    val clientJarNameLocal = enigmaClientJarName
    val targetClientJar = serverJarDir.file(enigmaClientJarName)

    workingDir = clientDir.asFile
    commandLine("cmd", "/c", "gradlew.bat", "build", "--console=plain")

    inputs.dir(clientDir.dir("src"))
    inputs.files(
        clientDir.file("build.gradle.kts"),
        clientDir.file("settings.gradle.kts"),
        clientDir.file("gradle.properties")
    )
    outputs.dir(clientBuildLibs)
    outputs.file(targetClientJar)

    doLast {
        val built = clientBuildLibs.file(clientJarNameLocal).asFile
        if (!built.isFile) {
            error("EnigmaClient-Jar nicht gefunden: ${built.absolutePath}")
        }

        val target = targetClientJar.asFile
        target.parentFile.mkdirs()
        built.copyTo(target, overwrite = true)
        println("EnigmaClient-Jar kopiert nach: ${target.absolutePath}")
    }
}
