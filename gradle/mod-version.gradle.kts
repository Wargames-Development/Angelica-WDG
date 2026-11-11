import PersistLocalBuildNumber

val runtimeTasks = setOf("assemble", "build", "reobfJar")
val start = gradle.startParameter
val requestedTasks = start.taskNames
val packaging = requestedTasks.any { it.removePrefix(":") in runtimeTasks } &&
    requestedTasks.none { it.substringAfterLast(':').startsWith("runClient") || it.substringAfterLast(':').startsWith("runServer") } &&
    start.excludedTaskNames.none { it.removePrefix(":") in runtimeTasks }

val explicitNumber = start.projectProperties["modBuildNumber"]
val currentNumber = providers.gradleProperty("modBuildNumber").get().toInt()
val advanceNumber = packaging && explicitNumber == null
val buildNumber = if (advanceNumber) Math.addExact(currentNumber, 1) else currentNumber
val modVersion = "${providers.gradleProperty("modVersionBase").get()}_X$buildNumber"
extra["modVersion"] = modVersion

if (advanceNumber) {
    extra["nextLocalBuildNumber"] = buildNumber
    extra["expectedLocalBuildJar"] = "libs/angelica-$modVersion.jar"
    apply<PersistLocalBuildNumber>()
}
