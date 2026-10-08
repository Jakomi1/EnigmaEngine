import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.core.tasks.patchroulette.AbstractPatchRouletteTask

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

abstract class CopyPaperclipJar : DefaultTask() {
    @get:InputDirectory
    abstract val libsDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val paperclipFile = libsDir.get().asFile
            .listFiles()
            ?.filter { it.name.startsWith("canvas-paperclip") && it.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("Paperclip-Jar nicht gefunden in ${libsDir.get()}")

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        paperclipFile.copyTo(target, overwrite = true)
        println("Server-Jar kopiert nach: ${target.absolutePath}")
    }
}

tasks.register<CopyPaperclipJar>("buildEnigmaEngine") {
    description = "Baut die Server-Jar und kopiert sie nach serverJar/EnigmaEngine.jar"
    group = "build"

    dependsOn(":canvas-server:createPaperclipJar")

    libsDir.set(file("canvas-server/build/libs"))
    outputFile.set(file("serverJar/EnigmaEngine.jar"))
}

tasks.register<Exec>("buildEnigmaClient") {
    description = "Baut die EnigmaClient-Fabric-Mod (enigma-client/)"
    group = "build"

    workingDir = layout.projectDirectory.dir("enigma-client").asFile
    commandLine("cmd", "/c", "gradlew.bat", "build", "--console=plain")

    inputs.dir(layout.projectDirectory.dir("enigma-client/src"))
    inputs.files(
        layout.projectDirectory.file("enigma-client/build.gradle.kts"),
        layout.projectDirectory.file("enigma-client/settings.gradle.kts"),
        layout.projectDirectory.file("enigma-client/gradle.properties")
    )
    outputs.dir(layout.projectDirectory.dir("enigma-client/build/libs"))
}
