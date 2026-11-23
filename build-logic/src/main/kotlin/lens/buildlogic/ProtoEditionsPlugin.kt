package lens.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Registers `downgradeProtoEditions`: `src/proto` (editions sources, vendored
 * as-is) to `build/generated/proto-downgraded` (what Wire reads).
 */
class ProtoEditionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("downgradeProtoEditions", DowngradeProtoEditions::class.java) {
            sourceDir.set(project.layout.projectDirectory.dir("src/proto"))
            outputDir.set(project.layout.buildDirectory.dir("generated/proto-downgraded"))
        }
    }
}

@CacheableTask
abstract class DowngradeProtoEditions : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val files = sourceDir.get().asFile.listFiles { f -> f.extension == "proto" }
            .orEmpty().sortedBy { it.name }
        if (files.isEmpty()) throw GradleException("no .proto files in ${sourceDir.get()}")

        val texts = files.associate { it.name to it.readText() }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }

        val bySyntax = mutableMapOf<String, Int>()
        val overrides = mutableListOf<String>()
        for ((name, result) in ProtoEditionsDowngrader.downgradeAll(texts)) {
            result.syntax?.let { bySyntax.merge(it, 1, Int::plus) }
            overrides += result.overrides
            out.resolve(name).writeText(result.source)
        }

        val accepted = ProtoEditionsDowngrader.ACCEPTED_PER_FIELD_OVERRIDES
        val added = overrides.toSet() - accepted
        val gone = accepted - overrides.toSet()
        if (added.isNotEmpty() || gone.isNotEmpty()) {
            throw GradleException(
                "per-field presence overrides in ${sourceDir.get()} have moved.\n" +
                    "  new, decide how each should be downgraded: $added\n" +
                    "  gone, so ACCEPTED_PER_FIELD_OVERRIDES is stale: $gone",
            )
        }
        logger.lifecycle("downgraded ${files.size} protos, editions files rewritten as: $bySyntax")
    }
}
