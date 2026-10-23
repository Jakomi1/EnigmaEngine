
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class CopyPaperclipJar : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libsDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun copyPaperclipJar() {
        val libsDirectory = libsDir.get().asFile

        val paperclipFile = libsDirectory.listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("canvas-paperclip") &&
                    it.name.endsWith(".jar")
            }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException(
                "Paperclip-Jar nicht gefunden in ${libsDirectory.absolutePath}"
            )

        val target = outputFile.get().asFile
        Files.createDirectories(target.toPath().parent)

        Files.copy(
            paperclipFile.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        logger.lifecycle("Server-Jar kopiert nach: ${target.absolutePath}")
    }
}
