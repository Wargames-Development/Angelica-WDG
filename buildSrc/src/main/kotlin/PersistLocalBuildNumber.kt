import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.flow.BuildWorkResult
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSuccessResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

// Gradle restores the flow action and its service from the same buildSrc classloader.
abstract class PersistLocalBuildNumber : Plugin<Project> {
    @get:Inject abstract val flowScope: FlowScope
    @get:Inject abstract val flowProviders: FlowProviders
    @get:Inject abstract val taskEvents: BuildEventsListenerRegistry

    override fun apply(target: Project) {
        val extra = target.extensions.extraProperties
        val buildNumber = extra.get("nextLocalBuildNumber") as Int
        val runtimeJar = extra.get("expectedLocalBuildJar") as String
        val packageStatus = target.gradle.sharedServices.registerIfAbsent("numberedModPackage", PackageStatus::class.java) {}
        taskEvents.onTaskCompletion(packageStatus)
        flowScope.always(SaveBuildNumber::class.java) {
            parameters.buildResult.set(flowProviders.buildWorkResult)
            parameters.buildNumber.set(buildNumber)
            parameters.propertiesPath.set(target.layout.projectDirectory.file("gradle.properties").asFile.absolutePath)
            parameters.runtimeJarPath.set(target.layout.buildDirectory.file(runtimeJar).map { it.asFile.absolutePath })
        }
    }
}

abstract class PackageStatus : BuildService<BuildServiceParameters.None>, OperationCompletionListener {
    @Volatile var reobfuscated = false
        private set

    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent && event.descriptor.taskPath == ":reobfJar" && event.result is TaskSuccessResult) {
            reobfuscated = true
        }
    }
}

abstract class SaveBuildNumber : FlowAction<SaveBuildNumber.Parameters> {
    interface Parameters : FlowParameters {
        @get:Input val buildResult: Property<BuildWorkResult>
        @get:Input val buildNumber: Property<Int>
        @get:Input val propertiesPath: Property<String>
        @get:Input val runtimeJarPath: Property<String>
        @get:ServiceReference("numberedModPackage") val packageStatus: Property<PackageStatus>
    }

    override fun execute(parameters: Parameters) {
        if (parameters.buildResult.get().failure.isPresent || !parameters.packageStatus.get().reobfuscated ||
            !File(parameters.runtimeJarPath.get()).isFile) return
        val next = parameters.buildNumber.get()
        val file = File(parameters.propertiesPath.get())
        val content = file.readText()
        val entry = Regex("(?m)^modBuildNumber[ \\t]*=[ \\t]*([0-9]+)(?=[ \\t]*\\r?$)").find(content)
        val number = requireNotNull(entry?.groups?.get(1)) { "Missing or invalid modBuildNumber in gradle.properties" }
        if (number.value.toInt() == next) return
        check(number.value.toInt() == next - 1) { "modBuildNumber changed during packaging" }
        val temporary = Files.createTempFile(file.toPath().parent, "gradle.properties-", ".tmp")
        try {
            Files.writeString(temporary, content.replaceRange(number.range, next.toString()))
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Logging.getLogger(SaveBuildNumber::class.java).lifecycle("Saved successful build number X$next")
    }
}
