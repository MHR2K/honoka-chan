package me.killkiss.honokactrl

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.killkiss.honokactrl.ui.GmWebScreen
import me.killkiss.honokactrl.ui.MainScreen
import me.killkiss.honokactrl.ui.MainViewModel
import me.killkiss.honokactrl.theme.HonokaControlTheme
import me.killkiss.honokactrl.storage.RuntimePreparer

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingDirectoryPicker = false
    private var pendingStartServer = false
    private var batteryOptimizationDialogShown = false
    private val runtimePreparer by lazy { RuntimePreparer(this) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleSelectedDirectory(uri)
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                exportBackupToUri(uri)
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                confirmImportBackup(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HonokaControlTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showGmConsole by remember { mutableStateOf(false) }
                    val uiState by viewModel.uiState.collectAsState()
                    val adminUrl = if (uiState.status.localUrl.isNotBlank()) {
                        "${uiState.status.localUrl}/admin/index"
                    } else {
                        "http://127.0.0.1:8080/admin/index"
                    }

                    if (showGmConsole) {
                        GmWebScreen(
                            url = adminUrl,
                            onClose = { showGmConsole = false },
                            onOpenExternal = { openLocalUrl(adminUrl) },
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onToggleServer = { handleToggleServer() },
                            onOpenGmConsole = { showGmConsole = true },
                            onLaunchGame = { launchGame() },
                            onPickDataDirectory = { openDataDirectoryPicker() },
                            onExportBackup = { openExportBackup() },
                            onImportBackup = { openImportBackup() },
                        )
                    }
                }
            }
        }
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAllFilesAccess(hasAllFilesAccess())
        viewModel.refreshStatus()
        maybeShowBatteryOptimizationDialog()
        if (pendingStartServer && hasAllFilesAccess()) {
            pendingStartServer = false
            viewModel.toggleServer()
        }
        if (pendingDirectoryPicker && hasAllFilesAccess()) {
            pendingDirectoryPicker = false
            openDocumentTreeLauncher.launch(null)
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeShowBatteryOptimizationDialog() {
        if (batteryOptimizationDialogShown || isIgnoringBatteryOptimizations()) {
            return
        }

        batteryOptimizationDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("允许后台运行")
            .setMessage("当前还未为本应用开启后台保活相关设置。建议手动在系统设置中开启忽略电池优化、自启动和后台运行。服务现在仍可正常启动；如果在国产系统上退到后台后仍会断开，还需要在最近任务中将本应用锁定。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun openDataDirectoryPicker() {
        if (!hasAllFilesAccess()) {
            pendingDirectoryPicker = true
            viewModel.showMessage("请先授予全部文件访问权限，授权后会自动继续选择目录")
            openAllFilesAccessSettings()
            return
        }
        openDocumentTreeLauncher.launch(null)
    }

    private fun handleToggleServer() {
        if (viewModel.uiState.value.status.running) {
            viewModel.toggleServer()
            return
        }
        if (!hasAllFilesAccess()) {
            pendingStartServer = true
            viewModel.showMessage("请先授予全部文件访问权限，授权后会自动继续启动服务")
            openAllFilesAccessSettings()
            return
        }
        viewModel.toggleServer()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun openLocalUrl(targetUrl: String? = null) {
        val url = if (!targetUrl.isNullOrBlank()) {
            targetUrl
        } else {
            val base = viewModel.uiState.value.status.localUrl
            if (base.isNotBlank()) "$base/admin/index" else "http://127.0.0.1:8080/admin/index"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.showMessage("没有可用于打开网页的浏览器")
        }
    }

    private fun launchGame() {
        val candidates = listOf("klb.android.lovelivecn", "klb.android.lovelive")
        for (pkg in candidates) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (e: Exception) {
                    viewModel.showMessage("启动游戏失败: ${e.message}")
                    return
                }
            }
        }
        viewModel.showMessage("未检测到游戏安装包 (klb.android.lovelivecn)，请确保已在手机上安装 LoveLive_9.7.1_patched.apk！")
    }

    private fun openExportBackup() {
        if (!ensureBackupOperationAllowed()) {
            return
        }
        exportBackupLauncher.launch("honoka-data.db")
    }

    private fun openImportBackup() {
        if (!ensureBackupOperationAllowed()) {
            return
        }
        importBackupLauncher.launch(arrayOf("*/*"))
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun ensureBackupOperationAllowed(): Boolean {
        if (viewModel.uiState.value.status.running) {
            viewModel.showMessage("请先停止服务后再导入或导出备份")
            return false
        }
        return true
    }

    private fun handleSelectedDirectory(uri: Uri?) {
        if (uri == null) {
            return
        }

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        val path = treeUriToPath(uri)
        if (path != null) {
            viewModel.setDataDirPath(path)
        } else {
            viewModel.showMessage("无法解析所选目录，请改用外部存储目录")
        }
    }

    private fun treeUriToPath(uri: Uri): String? {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = documentId.split(":", limit = 2)
        val volume = parts.getOrNull(0) ?: return null
        val relativePath = parts.getOrNull(1).orEmpty()

        val basePath = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }

        return if (relativePath.isBlank()) {
            basePath
        } else {
            File(basePath, relativePath).absolutePath
        }
    }

    private fun exportBackupToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dataDbFile = ensureRuntimeDataDbFile(requireExisting = true)
                checkpointUserDatabase(dataDbFile)
                contentResolver.openOutputStream(uri)?.use { output ->
                    dataDbFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: error("无法打开导出目标文件")
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    viewModel.showMessage("备份已导出")
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    viewModel.showMessage(it.message ?: "导出备份失败")
                }
            }
        }
    }

    private fun confirmImportBackup(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("导入备份")
            .setMessage("导入备份会覆盖当前用户数据，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                importBackupFromUri(uri)
            }
            .show()
    }

    private fun importBackupFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val dataDbFile = ensureRuntimeDataDbFile(requireExisting = false)
                val tempFile = File(dataDbFile.parentFile, "${dataDbFile.name}.importing")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("无法打开备份文件")

                deleteUserDatabaseSidecars(dataDbFile)
                if (dataDbFile.exists()) {
                    dataDbFile.delete()
                }
                if (!tempFile.renameTo(dataDbFile)) {
                    tempFile.inputStream().use { input ->
                        dataDbFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                }
                deleteUserDatabaseSidecars(dataDbFile)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    viewModel.showMessage("备份已导入，下次启动服务时生效")
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    viewModel.showMessage(it.message ?: "导入备份失败")
                }
            }
        }
    }

    private fun ensureRuntimeDataDbFile(requireExisting: Boolean): File {
        val runtimeInfo = runtimePreparer.prepare(viewModel.uiState.value.dataDirPath, forceRedeploy = false)
            .getOrElse { throw it }
        val dataDbFile = File(runtimeInfo.runtimeRoot, "assets/data.db")
        dataDbFile.parentFile?.mkdirs()
        if (requireExisting && !dataDbFile.exists()) {
            throw IllegalStateException("当前没有可用的用户数据文件")
        }
        return dataDbFile
    }

    private fun checkpointUserDatabase(dataDbFile: File) {
        val db = SQLiteDatabase.openDatabase(dataDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use {
            it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                while (cursor.moveToNext()) {
                }
            }
        }
    }

    private fun deleteUserDatabaseSidecars(dataDbFile: File) {
        File(dataDbFile.parentFile, "${dataDbFile.name}-wal").delete()
        File(dataDbFile.parentFile, "${dataDbFile.name}-shm").delete()
    }
}
