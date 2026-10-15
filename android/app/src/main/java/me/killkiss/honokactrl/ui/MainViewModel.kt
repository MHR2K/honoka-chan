package me.killkiss.honokactrl.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.killkiss.honokactrl.jni.NativeBridge
import me.killkiss.honokactrl.jni.NativeHealthInfo
import me.killkiss.honokactrl.jni.NativeReloadResult
import me.killkiss.honokactrl.jni.NativeServerStatus
import me.killkiss.honokactrl.service.HonokaServerService
import me.killkiss.honokactrl.storage.RuntimePreparer
import me.killkiss.honokactrl.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class MainUiState(
    val dataDirPath: String = SettingsStore.DEFAULT_DATA_DIR_PATH,
    val runtimeRoot: String = "",
    val configPath: String = "",
    val deployedBundleHash: String = "",
    val allFilesAccessGranted: Boolean = false,
    val status: NativeServerStatus = NativeServerStatus(),
    val health: NativeHealthInfo = NativeHealthInfo(),
    val unlockAllSpecialRotation: Boolean = false,
    val message: String = "",
    val busy: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "HonokaServer"
    }

    private val settingsStore = SettingsStore(application)
    private val runtimePreparer = RuntimePreparer(application)
    private var messageClearJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            dataDirPath = settingsStore.loadDataDirPath(),
            allFilesAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager(),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshStatus()
        refreshRuntimePaths()
    }

    fun setDataDirPath(path: String) {
        val normalizedPath = path.ifBlank { SettingsStore.DEFAULT_DATA_DIR_PATH }
        settingsStore.saveDataDirPath(normalizedPath)
        _uiState.value = _uiState.value.copy(dataDirPath = normalizedPath)
        applyDataDirMount()
    }

    fun showMessage(message: String) {
        pushMessage(message)
    }

    fun toggleServer() {
        if (uiState.value.status.running) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun startServer() {
        if (!uiState.value.allFilesAccessGranted) {
            Log.w(TAG, "start aborted: all files access is not granted")
            pushMessage("请先授予全部文件访问权限")
            return
        }
        if (uiState.value.busy || uiState.value.status.running) {
            Log.w(TAG, "start ignored: server is already busy or running")
            return
        }

        Log.i(TAG, "start requested, dataDir=${uiState.value.dataDirPath}")
        prepareRuntime { runtimeRoot ->
            val context = getApplication<Application>()
            val intent = HonokaServerService.startIntent(context, runtimeRoot)
            try {
                Log.i(TAG, "starting foreground service, runtimeRoot=$runtimeRoot")
                ContextCompat.startForegroundService(context, intent)
                _uiState.value = _uiState.value.copy(busy = true)
                pushMessage("正在启动服务", autoClearMillis = null)
                refreshServerState(
                    initialDelayMillis = 300,
                    retryCount = 20,
                    retryDelayMillis = 300,
                    expectedRunning = true,
                    updateMessage = true,
                )
            } catch (e: Throwable) {
                Log.e(TAG, "failed to start foreground service", e)
                _uiState.value = _uiState.value.copy(busy = false)
                pushMessage(e.message ?: "启动前台服务失败")
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "stop requested")
            _uiState.value = _uiState.value.copy(busy = true)
            val context = getApplication<Application>()
            context.startService(HonokaServerService.stopIntent(context))
            refreshServerState(
                initialDelayMillis = 200,
                retryCount = 6,
                retryDelayMillis = 300,
                expectedRunning = false,
                updateMessage = true,
            )
        }
    }

    fun refreshStatus(delayed: Boolean = false) {
        refreshServerState(initialDelayMillis = if (delayed) 800 else 0, updateMessage = false)
    }

    fun refreshAllFilesAccess(granted: Boolean) {
        _uiState.value = _uiState.value.copy(allFilesAccessGranted = granted)
    }

    fun updateUnlockAllSpecialRotation(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                busy = true,
                unlockAllSpecialRotation = enabled,
            )

            val configFile = File(_uiState.value.configPath.ifBlank {
                File(getApplication<Application>().filesDir, RuntimePreparer.RUNTIME_DIR_NAME)
                    .resolve("config.json")
                    .absolutePath
            })

            runCatching {
                writeUnlockAllSpecialRotation(configFile, enabled)
            }.onFailure {
                Log.e(TAG, "failed to update config", it)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        unlockAllSpecialRotation = !enabled,
                    )
                    pushMessage(it.message ?: "更新配置失败")
                }
                return@launch
            }

            if (!uiState.value.status.running) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(busy = false)
                    pushMessage("设置已保存，下次启动服务时生效")
                }
                return@launch
            }

            val result: NativeReloadResult = NativeBridge.reload()
            Log.i(TAG, "reload requested by config switch: success=${result.success}, message=${result.message}")
            withContext(Dispatchers.Main) {
                if (result.success) {
                    pushMessage("设置已保存并重载")
                    refreshServerState(initialDelayMillis = 200, updateMessage = false)
                } else {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                    )
                    pushMessage(result.message.ifBlank { "设置已保存，但重载配置失败" })
                }
            }
        }
    }

    private fun applyDataDirMount() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "apply data dir mount: ${_uiState.value.dataDirPath}")
            _uiState.value = _uiState.value.copy(busy = true)

            val result = runtimePreparer.prepare(_uiState.value.dataDirPath, forceRedeploy = false)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(busy = false)
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(
                        runtimeRoot = it.runtimeRoot.absolutePath,
                        configPath = it.configFile.absolutePath,
                        deployedBundleHash = it.deployedBundleHash,
                    )
                    syncConfigState(it.configFile)
                    pushMessage("数据目录已保存并挂载")
                }.onFailure {
                    Log.e(TAG, "apply data dir mount failed", it)
                    pushMessage(it.message ?: "目录挂载失败")
                }
            }
        }
    }

    private fun prepareRuntime(onSuccess: ((String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "prepare runtime")
            _uiState.value = _uiState.value.copy(busy = true)
            settingsStore.saveDataDirPath(_uiState.value.dataDirPath)

            val result = runtimePreparer.prepare(_uiState.value.dataDirPath, forceRedeploy = false)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(busy = false)
                result.onSuccess {
                    Log.i(TAG, "runtime ready: root=${it.runtimeRoot}, mounted=${it.mountedDataDir}")
                    _uiState.value = _uiState.value.copy(
                        runtimeRoot = it.runtimeRoot.absolutePath,
                        configPath = it.configFile.absolutePath,
                        deployedBundleHash = it.deployedBundleHash,
                    )
                    syncConfigState(it.configFile)
                    pushMessage("运行时已准备完成")
                    onSuccess?.invoke(it.runtimeRoot.absolutePath)
                }.onFailure {
                    Log.e(TAG, "runtime prepare failed", it)
                    pushMessage(it.message ?: "运行时准备失败")
                }
            }
        }
    }

    private fun refreshRuntimePaths() {
        val runtimeRoot = File(getApplication<Application>().filesDir, RuntimePreparer.RUNTIME_DIR_NAME)
        _uiState.value = _uiState.value.copy(
            runtimeRoot = runtimeRoot.absolutePath,
            configPath = File(runtimeRoot, "config.json").absolutePath,
            deployedBundleHash = RuntimePreparer.readDeployedBundleHash(runtimeRoot),
        )
        syncConfigState(File(runtimeRoot, "config.json"))
    }

    private fun refreshServerState(
        initialDelayMillis: Long = 0,
        retryCount: Int = 1,
        retryDelayMillis: Long = 0,
        expectedRunning: Boolean? = null,
        updateMessage: Boolean = false,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (initialDelayMillis > 0) {
                delay(initialDelayMillis)
            }

            var status = NativeBridge.status()
            var health = NativeBridge.health()
            for (attempt in 1 until retryCount.coerceAtLeast(1)) {
                if (isExpectedStateReached(status, health, expectedRunning)) {
                    break
                }
                if (retryDelayMillis > 0) {
                    delay(retryDelayMillis)
                }
                status = NativeBridge.status()
                health = NativeBridge.health()
            }

            Log.d(
                TAG,
                "server state refreshed: running=${status.running}, health=${health.status}, mainDb=${health.mainDb}, userDb=${health.userDb}, error=${status.lastError}",
            )

            withContext(Dispatchers.Main) {
                val message = if (updateMessage) {
                    buildStatusMessage(status, health, expectedRunning)
                } else {
                    _uiState.value.message
                }
                _uiState.value = _uiState.value.copy(
                    status = status,
                    health = health,
                    busy = false,
                    message = if (updateMessage) message else _uiState.value.message,
                )
                syncConfigState(File(_uiState.value.configPath))
                if (updateMessage && message.isNotBlank()) {
                    scheduleMessageClear(5000)
                }
                refreshRuntimePaths()
            }
        }
    }

    private fun isExpectedStateReached(
        status: NativeServerStatus,
        health: NativeHealthInfo,
        expectedRunning: Boolean?,
    ): Boolean {
        return when (expectedRunning) {
            true -> status.running && health.status != "stopped" && health.mainDb != "not initialized" && health.userDb != "not initialized"
            false -> !status.running
            null -> true
        }
    }

    private fun buildStatusMessage(
        status: NativeServerStatus,
        health: NativeHealthInfo,
        expectedRunning: Boolean?,
    ): String {
        if (status.lastError.isNotBlank()) {
            return status.lastError
        }

        return when (expectedRunning) {
            true -> {
                if (status.running && health.status != "stopped") {
                    "服务运行中"
                } else {
                    "服务启动中，请稍后刷新"
                }
            }

            false -> "服务已停止"
            null -> {
                if (status.running) {
                    "服务运行中"
                } else {
                    _uiState.value.message
                }
            }
        }
    }

    private fun pushMessage(message: String, autoClearMillis: Long? = 5000) {
        messageClearJob?.cancel()
        _uiState.value = _uiState.value.copy(message = message)
        if (autoClearMillis != null) {
            scheduleMessageClear(autoClearMillis)
        }
    }

    private fun syncConfigState(configFile: File) {
        if (!configFile.exists()) {
            return
        }

        runCatching {
            val json = JSONObject(configFile.readText())
            json.optJSONObject("settings")?.optBoolean("unlock_all_special_rotation", false) ?: false
        }.onSuccess { enabled ->
            _uiState.value = _uiState.value.copy(unlockAllSpecialRotation = enabled)
        }.onFailure {
            Log.w(TAG, "failed to parse config state", it)
        }
    }

    private fun writeUnlockAllSpecialRotation(configFile: File, enabled: Boolean) {
        configFile.parentFile?.mkdirs()
        val json = if (configFile.exists()) {
            runCatching { JSONObject(configFile.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val settings = json.optJSONObject("settings") ?: JSONObject()
        if (!json.has("app_name")) {
            json.put("app_name", "honoka-chan")
        }
        settings.put("listen_port", settings.optString("listen_port", "8080"))
        settings.put("cdn_server", settings.optString("cdn_server", "http://127.0.0.1:8080/static"))
        settings.put("reload_token", settings.optString("reload_token", ""))
        settings.put("unlock_all_special_rotation", enabled)
        json.put("settings", settings)
        configFile.writeText(json.toString(4) + "\n")
    }

    private fun scheduleMessageClear(delayMillis: Long) {
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(delayMillis)
            _uiState.value = _uiState.value.copy(message = "")
        }
    }
}
