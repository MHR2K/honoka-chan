package me.killkiss.honokactrl.storage

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class RuntimePreparer(private val context: Context) {
    fun prepare(dataDirPath: String, forceRedeploy: Boolean = false): Result<RuntimeInfo> {
        return runCatching {
            val runtimeRoot = File(context.filesDir, RUNTIME_DIR_NAME)
            val bundleHash = calculateBundleHash()
            Log.i(TAG, "prepare runtime root=${runtimeRoot.absolutePath}, dataDir=$dataDirPath, forceRedeploy=$forceRedeploy")
            if (forceRedeploy || shouldRedeployBundle(runtimeRoot, bundleHash)) {
                redeployBundle(runtimeRoot, bundleHash)
            }
            mountAndroidDir(runtimeRoot, File(dataDirPath.ifBlank { SettingsStore.DEFAULT_DATA_DIR_PATH }))
            RuntimeInfo(
                runtimeRoot = runtimeRoot,
                mountedDataDir = File(dataDirPath.ifBlank { SettingsStore.DEFAULT_DATA_DIR_PATH }),
                configFile = File(runtimeRoot, "config.json"),
                deployedBundleHash = bundleHash,
            )
        }
    }

    private fun redeployBundle(runtimeRoot: File, bundleHash: String) {
        Log.i(TAG, "redeploy bundle into ${runtimeRoot.absolutePath}")
        val preservedFiles = PRESERVED_RUNTIME_FILES.mapNotNull { relativePath ->
            File(runtimeRoot, relativePath).takeIf { it.exists() }?.let { relativePath to it.readBytes() }
        }.toMap()

        // 绝不误删外部目录：先显式断开外部 archives 软链接
        val archivesLink = File(runtimeRoot, "static/Android/archives")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.deleteIfExists(archivesLink.toPath())
            } else {
                archivesLink.delete()
            }
        } catch (_: Throwable) {
            archivesLink.delete()
        }

        if (runtimeRoot.exists()) {
            safeDeleteDirectory(runtimeRoot)
        }
        runtimeRoot.mkdirs()
        File(runtimeRoot, "assets").mkdirs()
        File(runtimeRoot, "static").mkdirs()

        context.assets.open(BUNDLE_ASSET_NAME).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    if (normalizedName.isNotEmpty()) {
                        val outFile = File(runtimeRoot, normalizedName)
                        if (preservedFiles.containsKey(normalizedName)) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (entry.isDirectory || normalizedName.endsWith("/")) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output ->
                                zip.copyTo(output)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        preservedFiles.forEach { (relativePath, data) ->
            File(runtimeRoot, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(data)
            }
        }

        File(runtimeRoot, BUNDLE_MARKER_FILE).writeText(bundleHash)
        Log.i(TAG, "bundle deployed successfully")
    }

    private fun shouldRedeployBundle(runtimeRoot: File, bundleHash: String): Boolean {
        val markerFile = File(runtimeRoot, BUNDLE_MARKER_FILE)
        val mainDb = File(runtimeRoot, "assets/main.db")
        if (!markerFile.exists() || !mainDb.exists()) {
            Log.i(TAG, "bundle marker or main.db missing, redeploy required")
            return true
        }

        val deployedHash = runCatching { markerFile.readText().trim() }.getOrDefault("")
        val changed = deployedHash != bundleHash
        if (changed) {
            Log.i(TAG, "runtime bundle hash changed, redeploy required")
        }
        return changed
    }

    private fun calculateBundleHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(BUNDLE_ASSET_NAME).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mountAndroidDir(runtimeRoot: File, targetDir: File) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        Log.i(TAG, "mount Android dir target=${targetDir.absolutePath}")

        val androidDir = File(runtimeRoot, "static/Android")
        androidDir.mkdirs()

        val archivesLink = File(androidDir, "archives")

        // 绝对安全地移除软链接，绝不调用 deleteRecursively，绝不伤及外部存储中的数据文件
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.deleteIfExists(archivesLink.toPath())
            } else {
                archivesLink.delete()
            }
        } catch (e: Throwable) {
            archivesLink.delete()
        }

        // 智能兼容多种存放形式：
        val resolvedArchivesDir = when {
            File(targetDir, "archives").isDirectory -> File(targetDir, "archives")
            File(targetDir, "list_CN_Android").isDirectory -> File(targetDir, "list_CN_Android")
            else -> targetDir
        }

        try {
            Os.symlink(resolvedArchivesDir.absolutePath, archivesLink.absolutePath)
            Log.i(TAG, "symlink created: ${archivesLink.absolutePath} -> ${resolvedArchivesDir.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create symlink: ${archivesLink.absolutePath} -> ${resolvedArchivesDir.absolutePath}", e)
        }
    }

    private fun safeDeleteDirectory(dir: File) {
        if (!dir.exists()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(d)
                        return FileVisitResult.CONTINUE
                    }
                })
                return
            } catch (e: Throwable) {
                Log.w(TAG, "walkFileTree delete failed, fallback", e)
            }
        }
        // 降级清理：遇软链接只单删链接本身，不递归其目标
        dir.listFiles()?.forEach { file ->
            if (isSymlink(file)) {
                file.delete()
            } else if (file.isDirectory) {
                safeDeleteDirectory(file)
            } else {
                file.delete()
            }
        }
        dir.delete()
    }

    private fun isSymlink(file: File): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.isSymbolicLink(file.toPath())
        } else {
            try {
                file.canonicalFile != file.absoluteFile
            } catch (_: IOException) {
                false
            }
        }
    }

    companion object {
        private const val TAG = "HonokaServer"
        const val BUNDLE_ASSET_NAME = "honoka_runtime.zip"
        const val RUNTIME_DIR_NAME = "honoka-runtime"
        private const val BUNDLE_MARKER_FILE = ".bundle_ready"
        private val PRESERVED_RUNTIME_FILES = listOf(
            "config.json",
            "assets/data.db",
            "assets/data.db-wal",
            "assets/data.db-shm",
        )

        fun readDeployedBundleHash(runtimeRoot: File): String {
            return runCatching {
                File(runtimeRoot, BUNDLE_MARKER_FILE).takeIf { it.exists() }?.readText()?.trim().orEmpty()
            }.getOrDefault("")
        }
    }
}

data class RuntimeInfo(
    val runtimeRoot: File,
    val mountedDataDir: File,
    val configFile: File,
    val deployedBundleHash: String,
)
