plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
    delete(rootProject.layout.projectDirectory.dir("release-aab"))
}

val exportReleaseAab by tasks.registering(Copy::class) {
    dependsOn(":app:bundleRelease")
    from(layout.projectDirectory.dir("app/build/outputs/bundle/release"))
    include("*.aab")
    into(layout.projectDirectory.dir("release-aab"))
}

tasks.register("bundleRelease") {
    dependsOn(":app:bundleRelease", exportReleaseAab)
}

tasks.register("assembleRelease") {
    dependsOn(":app:assembleRelease", ":app:bundleRelease", exportReleaseAab)
}

tasks.register("productionAab") {
    dependsOn(exportReleaseAab)
}

tasks.register("aabRelease") {
    dependsOn(exportReleaseAab)
}

defaultTasks("productionAab")
