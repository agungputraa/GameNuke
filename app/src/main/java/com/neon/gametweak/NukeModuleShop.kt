package com.neon.gametweak

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal data class NukeShopModule(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val developer: String,
    val version: String,
    val support: String,
    val minAndroid: Int,
    val url: String,
    val fileName: String,
    val sha256: String,
    val entryExec: String,
    val entryDelete: String,
)

internal data class NukeModuleShopState(
    val modules: List<NukeShopModule> = emptyList(),
    val installedIds: Set<String> = emptySet(),
    val activeIds: Set<String> = emptySet(),
    val busyIds: Set<String> = emptySet(),
    val blockedReasons: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val message: String = "MODULESHOP STANDBY",
    val catalogTrusted: Boolean = false,
)

/**
 * Hash-pinned ModuleShop loader and installer.
 *
 * Nothing is extracted outside app-private storage. A signed RemoteNuke definition pins the
 * catalog hash, the catalog pins every archive hash, and every archive must contain exactly two
 * regular files: exec.sh and del.sh. Runtime commands are serialized so repeated taps cannot queue
 * downloads or shell sessions.
 */
internal class NukeModuleShopRepository(
    context: Context,
    private val adb: AdbManager,
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "module_shop")
    private val catalogFile = File(root, "catalog.json")
    private val moduleRoot = File(root, "installed")
    private val prefs = appContext.getSharedPreferences("NukeModuleShop", Context.MODE_PRIVATE)
    private val catalogMutex = Mutex()
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(
        NukeModuleShopState(
            installedIds = discoverInstalledIds(),
            activeIds = storedActiveOrder().toSet(),
        ),
    )
    val state: StateFlow<NukeModuleShopState> = _state.asStateFlow()

    @Volatile private var definition: NukeRemoteModuleShop = NukeRemoteModuleShop()
    @Volatile private var loadedCatalogSha256: String? = null

    suspend fun configure(remote: NukeRemoteModuleShop, force: Boolean = false) {
        if (!catalogMutex.tryLock()) return
        try {
            definition = remote
            if (!remote.enabled) {
                _state.update { it.copy(modules = emptyList(), loading = false, catalogTrusted = false, message = "MODULESHOP DISABLED") }
                return
            }
            root.mkdirs()
            moduleRoot.mkdirs()
            val current = _state.value
            if (!force && current.modules.isNotEmpty() && current.catalogTrusted && loadedCatalogSha256 == remote.catalogSha256) return
            _state.update { it.copy(loading = true, message = "VERIFYING MODULE CATALOG…") }

            val cached = runCatching {
                catalogFile.takeIf { it.isFile && sha256(it.readBytes()) == remote.catalogSha256 }
                    ?.readText(Charsets.UTF_8)
                    ?.let { parseCatalog(it, remote) }
            }.getOrNull()
            if (!cached.isNullOrEmpty()) publishCatalog(cached, "VERIFIED CACHED CATALOG")

            runCatching {
                val bytes = fetchBytes(remote.catalogUrl, remote.maxCatalogBytes, "application/json,text/plain")
                require(sha256(bytes) == remote.catalogSha256) { "Catalog digest does not match signed RemoteNuke config" }
                val parsed = parseCatalog(String(bytes, Charsets.UTF_8), remote)
                persistCatalogAtomically(bytes)
                publishCatalog(parsed, "${parsed.size} VERIFIED MODULES")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        loading = false,
                        catalogTrusted = cached?.isNotEmpty() == true,
                        message = if (cached?.isNotEmpty() == true) {
                            "OFFLINE • USING VERIFIED CATALOG"
                        } else {
                            "CATALOG REJECTED • ${safeMessage(error)}"
                        },
                    )
                }
            }
        } finally {
            catalogMutex.unlock()
        }
    }

    suspend fun install(moduleId: String): String {
        if (!operationMutex.tryLock()) return "Another module action is already running"
        try {
            val module = findModule(moduleId) ?: return "Module is not present in the catalog"
            setBusy(module.id, true, "DOWNLOADING ${module.name.uppercase(Locale.US)}…")
            return try {
                val bytes = fetchBytes(module.url, definition.maxArchiveBytes, "application/zip,application/octet-stream")
                val scripts = readVerifiedArchive(bytes, module)
                persistModuleAtomically(module, scripts)
                val installed = discoverInstalledIds()
                _state.update {
                    it.copy(
                        installedIds = installed,
                        blockedReasons = it.blockedReasons - module.id,
                        message = "INSTALLED • ${module.name}",
                    )
                }
                "Installed ${module.name}; switch it on when ready"
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val reason = safeMessage(error)
                _state.update {
                    it.copy(
                        blockedReasons = it.blockedReasons + (module.id to reason),
                        message = "INSTALL ERROR • $reason",
                    )
                }
                "Install error: $reason"
            } finally {
                setBusy(module.id, false)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun setEnabled(moduleId: String, enabled: Boolean): String {
        if (!operationMutex.tryLock()) return "Another module action is already running"
        try {
            val module = findModule(moduleId) ?: return "Module is not present in the catalog"
            val currentlyActive = module.id in _state.value.activeIds
            if (currentlyActive == enabled) return if (enabled) "Module already active" else "Module already inactive"
            if (module.id !in _state.value.installedIds) return "Install the module before enabling it"
            setBusy(module.id, true, if (enabled) "APPLYING ${module.name.uppercase(Locale.US)}…" else "RESTORING ${module.name.uppercase(Locale.US)}…")
            return try {
                val scriptName = if (enabled) module.entryExec else module.entryDelete
                val scriptFile = installedVersionDir(module).resolve(scriptName)
                require(scriptFile.isFile && scriptFile.length() in 1..MAX_SCRIPT_BYTES.toLong()) { "Installed script is missing or oversized" }
                val script = scriptFile.readText(Charsets.UTF_8)
                val result = adb.executeVerifiedModuleScript(module.id, scriptName, script)
                // Accept as long as adb returned exit-0; we don't require specific output keywords.
                val accepted = result.isSuccess && !result.timedOut
                if (!accepted) {
                    if (enabled) {
                        // A script can exit 0 after reporting partial OEM failures. Roll it back
                        // before returning the switch to OFF so optimistic UI never lies.
                        runCatching {
                            val rollback = installedVersionDir(module).resolve(module.entryDelete).readText(Charsets.UTF_8)
                            adb.executeVerifiedModuleScript(module.id, module.entryDelete, rollback)
                        }
                    }
                    error(result.output.takeLast(180).ifBlank { if (result.timedOut) "Module timed out" else "Device rejected the module" })
                }
                setActive(module.id, enabled)
                _state.update {
                    it.copy(
                        activeIds = storedActiveOrder().toSet(),
                        message = if (enabled) "ACTIVE • ${module.name}" else "RESTORED • ${module.name}",
                    )
                }
                if (enabled) "${module.name} is active" else "${module.name} restored its saved settings"
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val reason = safeMessage(error)
                _state.update { it.copy(message = "MODULE ERROR • $reason") }
                "Module error: $reason"
            } finally {
                setBusy(module.id, false)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /** Restore in reverse activation order so overlapping settings unwind like a stack. */
    suspend fun deactivateAll(): List<String> {
        val results = mutableListOf<String>()
        storedActiveOrder().asReversed().forEach { id ->
            if (id in _state.value.activeIds) results += setEnabled(id, false)
        }
        return results
    }

    private fun publishCatalog(modules: List<NukeShopModule>, message: String) {
        loadedCatalogSha256 = definition.catalogSha256
        _state.update {
            it.copy(
                modules = modules,
                installedIds = modules.filterTo(mutableListOf()) { module ->
                    val dir = installedVersionDir(module)
                    dir.resolve(module.entryExec).isFile && dir.resolve(module.entryDelete).isFile &&
                        dir.resolve("archive.sha256").readText().trim() == module.sha256
                }.mapTo(mutableSetOf()) { module -> module.id },
                activeIds = storedActiveOrder().toSet().intersect(modules.mapTo(mutableSetOf()) { module -> module.id }),
                loading = false,
                catalogTrusted = true,
                message = message,
            )
        }
    }

    private fun parseCatalog(json: String, remote: NukeRemoteModuleShop): List<NukeShopModule> {
        val array = JSONArray(json)
        require(array.length() in 1..remote.maxEntries) { "Catalog entry count is outside policy" }
        val ids = HashSet<String>()
        val files = HashSet<String>()
        return buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("Id")
                val file = item.getString("fileName")
                val digest = item.getString("sha256").lowercase(Locale.US)
                val url = item.getString("url")
                require(MODULE_ID.matches(id) && ids.add(id)) { "Invalid or duplicate module id" }
                require(file == "$id.zip" && files.add(file)) { "Invalid or duplicate module filename" }
                require(SHA256.matches(digest)) { "Invalid archive digest for $id" }
                require(item.optBoolean("safe", false)) { "Catalog did not mark $id as reviewed" }
                require(item.optString("entryExec") == "exec.sh" && item.optString("entryDelete") == "del.sh") { "Unexpected script entry for $id" }
                validateModuleUrl(url, file, remote)
                add(
                    NukeShopModule(
                        id = id,
                        name = item.optString("Nama Module", id).trim().take(72).ifBlank { id },
                        description = item.optString("des", "").trim().take(240),
                        category = item.optString("category", "Other").trim().take(40).ifBlank { "Other" },
                        developer = item.optString("dev", "Unknown").trim().take(40),
                        version = item.optString("version", "1").trim().take(40),
                        support = item.optString("support", "").trim().take(48),
                        minAndroid = item.optInt("minAndroid", 30).coerceIn(1, 100),
                        url = url,
                        fileName = file,
                        sha256 = digest,
                        entryExec = "exec.sh",
                        entryDelete = "del.sh",
                    ),
                )
            }
        }.sortedWith(compareBy<NukeShopModule> { it.category }.thenBy { it.name })
    }

    private fun validateModuleUrl(value: String, file: String, remote: NukeRemoteModuleShop) {
        val url = URL(value)
        require(url.protocol == "https" && url.query == null && url.ref == null) { "Module URL must be HTTPS without query or fragment" }
    }

    private fun readVerifiedArchive(bytes: ByteArray, module: NukeShopModule): Map<String, String> {
        val expected = setOf(module.entryExec, module.entryDelete)
        val scripts = LinkedHashMap<String, String>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(!entry.isDirectory && entry.name in expected && entry.name !in scripts) { "Unexpected ZIP entry ${entry.name}" }
                val target = File(moduleRoot, "candidate/${module.id}/${entry.name}")
                val base = File(moduleRoot, "candidate/${module.id}").canonicalFile
                require(target.canonicalFile.path.startsWith(base.path + File.separator)) { "ZIP path traversal rejected" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_UNCOMPRESSED_BYTES) { "Archive expands beyond policy" }
                    output.write(buffer, 0, read)
                }
                scripts[entry.name] = String(output.toByteArray(), Charsets.UTF_8).replace("\r\n", "\n")
                input.closeEntry()
            }
        }
        if (scripts.keys != expected) throw ZipException("Archive must contain exactly exec.sh and del.sh")
        return scripts
    }

    private fun persistCatalogAtomically(bytes: ByteArray) {
        root.mkdirs()
        val candidate = File(root, "catalog.tmp")
        candidate.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
        require(candidate.renameTo(catalogFile) || runCatching {
            candidate.copyTo(catalogFile, overwrite = true)
            candidate.delete()
            true
        }.getOrDefault(false)) { "Catalog cache activation failed" }
    }

    private fun persistModuleAtomically(module: NukeShopModule, scripts: Map<String, String>) {
        val versionDir = installedVersionDir(module)
        if (versionDir.isDirectory && scripts.keys.all { versionDir.resolve(it).isFile } &&
            versionDir.resolve("archive.sha256").takeIf(File::isFile)?.readText()?.trim() == module.sha256
        ) return
        val candidate = File(moduleRoot, ".candidate-${module.id}-${module.sha256.take(12)}")
        require(candidate.canonicalPath.startsWith(moduleRoot.canonicalPath + File.separator)) { "Invalid module destination" }
        if (candidate.exists()) candidate.deleteRecursively()
        require(candidate.mkdirs()) { "Cannot create module candidate directory" }
        try {
            scripts.forEach { (name, script) ->
                candidate.resolve(name).outputStream().use { stream ->
                    stream.write(script.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }
            candidate.resolve("archive.sha256").writeText(module.sha256, Charsets.US_ASCII)
            versionDir.parentFile?.mkdirs()
            if (versionDir.exists()) versionDir.deleteRecursively()
            require(candidate.renameTo(versionDir)) { "Atomic module activation failed" }
        } finally {
            if (candidate.exists()) candidate.deleteRecursively()
        }
    }

    private fun fetchBytes(value: String, maxBytes: Int, accept: String): ByteArray {
        val url = URL(value)
        require(url.protocol == "https") { "Only HTTPS URLs are allowed" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 4_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-transform")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Remote HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= maxBytes) { "Remote file exceeds size policy" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "Remote response exceeds size policy" }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun findModule(id: String): NukeShopModule? = _state.value.modules.firstOrNull { it.id == id }
    private fun installedVersionDir(module: NukeShopModule): File = File(moduleRoot, "${module.id}/${module.sha256}")

    private fun discoverInstalledIds(): Set<String> {
        if (!moduleRoot.isDirectory) return emptySet()
        return moduleRoot.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && MODULE_ID.matches(it.name) }
            .filter { idDir ->
                idDir.listFiles().orEmpty().any { version ->
                    version.isDirectory && version.resolve("exec.sh").isFile && version.resolve("del.sh").isFile
                        && version.resolve("archive.sha256").isFile
                }
            }
            .map { it.name }
            .toSet()
    }

    private fun storedActiveOrder(): List<String> = prefs.getString("active_order", "").orEmpty()
        .split(',').map(String::trim).filter(MODULE_ID::matches).distinct()

    private fun setActive(id: String, active: Boolean) {
        val order = storedActiveOrder().toMutableList().apply {
            remove(id)
            if (active) add(id)
        }
        prefs.edit().putString("active_order", order.joinToString(",")).commit()
    }

    private fun setBusy(id: String, busy: Boolean, message: String? = null) {
        _state.update {
            it.copy(
                busyIds = if (busy) it.busyIds + id else it.busyIds - id,
                message = message ?: it.message,
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

    private fun safeMessage(error: Throwable): String = error.message.orEmpty().trim()
        .replace(Regex("[\\r\\n\\t]+"), " ").take(180).ifBlank { error.javaClass.simpleName }

    private companion object {
        const val TRUSTED_HOST = "raw.githubusercontent.com"
        const val MAX_SCRIPT_BYTES = 96 * 1024
        const val MAX_UNCOMPRESSED_BYTES = MAX_SCRIPT_BYTES * 2
        val MODULE_ID = Regex("^[a-z0-9][a-z0-9_]{2,63}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
