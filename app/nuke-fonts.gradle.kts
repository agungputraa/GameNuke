// Font acquisition is a build dependency, not an in-app network operation.
// A failed download fails the build explicitly; no renamed substitute fonts are used.
val nukeFontResources = layout.buildDirectory.dir("generated/nukeFonts/res")
val prepareNukeFonts = tasks.register("prepareNukeFonts") {
    outputs.dir(nukeFontResources)
    outputs.dir(layout.buildDirectory.dir("generated/nukeFonts/licenses"))
    doLast {
        val directory = nukeFontResources.get().dir("font").asFile.apply { mkdirs() }
        val licenses = layout.buildDirectory.dir("generated/nukeFonts/licenses").get().asFile.apply { mkdirs() }
        fun fetch(url: String): ByteArray {
            val connection = java.net.URI(url).toURL().openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
            return connection.getInputStream().use { it.readBytes() }
        }
        fun install(family: String, url: String, names: List<String>) {
            val missing = names.any { !directory.resolve("$it.ttf").isFile }
            if (missing) {
                val bytes = fetch(url)
                check(bytes.size > 10000 && bytes.take(4) == listOf<Byte>(0, 1, 0, 0)) { "Invalid TrueType font from $url" }
                names.forEach { name -> directory.resolve("$name.ttf").writeBytes(bytes) }
            }
            val license = licenses.resolve("${family}_OFL.txt")
            if (!license.isFile) license.writeBytes(fetch("https://raw.githubusercontent.com/google/fonts/main/ofl/${family.lowercase()}/OFL.txt"))
        }
        install("Outfit", "https://raw.githubusercontent.com/google/fonts/main/ofl/outfit/Outfit%5Bwght%5D.ttf",
            listOf("outfit_bold", "outfit_semibold", "outfit_regular"))
        install("Inter", "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz,wght%5D.ttf",
            listOf("inter_medium", "inter_regular"))
    }
}
tasks.matching { it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("Resources")) }.configureEach {
    dependsOn(prepareNukeFonts)
}
