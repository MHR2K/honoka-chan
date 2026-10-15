package me.killkiss.honokactrl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import me.killkiss.honokactrl.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onToggleServer: () -> Unit,
    onOpenGmConsole: () -> Unit,
    onLaunchGame: () -> Unit,
    onPickDataDirectory: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务控制", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.status.running) "当前状态：运行中" else "当前状态：已停止",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.status.localUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "本地地址：${state.status.localUrl}",
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onOpenGmConsole, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Outlined.Language,
                                    contentDescription = "打开本地地址",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                )
                            }
                        }
                    }
                    if (state.status.startedAt.isNotBlank()) {
                        Text("启动时间：${state.status.startedAt}")
                    }
                    if (state.status.lastError.isNotBlank()) {
                        Text("最近错误：${state.status.lastError}", color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onToggleServer,
                            enabled = !state.busy,
                            modifier = Modifier.widthIn(min = 132.dp),
                        ) {
                            Icon(
                                if (state.status.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                            )
                            Text(if (state.status.running) "停止服务" else "启动服务")
                        }
                        if (state.status.running) {
                            Button(
                                onClick = onLaunchGame,
                                modifier = Modifier.widthIn(min = 132.dp),
                            ) {
                                Text("启动游戏")
                            }
                        }
                        Button(
                            onClick = onOpenGmConsole,
                            enabled = state.status.running,
                            modifier = Modifier.widthIn(min = 132.dp),
                        ) {
                            Icon(Icons.Outlined.Language, contentDescription = null)
                            Text("GM 控制台")
                        }
                        OutlinedButton(
                            onClick = { viewModel.refreshStatus() },
                            enabled = !state.busy,
                            modifier = Modifier.widthIn(min = 132.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text("刷新状态")
                        }
                    }
                }
            }

            if (state.message.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        text = state.message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务设置", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("解锁全部日替", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "切换后会直接写入配置文件，并在服务运行中自动重载。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.unlockAllSpecialRotation,
                            onCheckedChange = { viewModel.updateUnlockAllSpecialRotation(it) },
                            enabled = !state.busy,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("数据目录", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.dataDirPath,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Android 数据目录") },
                        leadingIcon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                        readOnly = true,
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickDataDirectory) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Text("选择目录")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("备份管理", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportBackup) {
                            Text("导出备份")
                        }
                        OutlinedButton(onClick = onImportBackup) {
                            Text("导入备份")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("运行时信息", style = MaterialTheme.typography.titleMedium)
                    LabelValue(label = "运行目录", value = state.runtimeRoot)
                    LabelValue(label = "配置文件", value = state.configPath)
                    LabelValue(label = "挂载目录", value = state.dataDirPath)
                    LabelValue(label = "资源包哈希", value = state.deployedBundleHash)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Health 状态", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.health.status == "ok",
                            onClick = {},
                            label = { Text(if (state.health.status.isBlank()) "未知" else state.health.status) },
                        )
                        FilterChip(
                            selected = state.health.mainDb == "ok",
                            onClick = {},
                            label = { Text("Main DB: ${if (state.health.mainDb == "ok") "connected" else "disconnected"}") },
                        )
                        FilterChip(
                            selected = state.health.userDb == "ok",
                            onClick = {},
                            label = { Text("User DB: ${if (state.health.userDb == "ok") "connected" else "disconnected"}") },
                        )
                    }
                    LabelValue(label = "应用名", value = state.health.appName)
                    LabelValue(label = "版本", value = state.health.version)
                    LabelValue(label = "监听端口", value = state.health.listenPort)
                    LabelValue(label = "启动时间", value = state.health.startedAt)
                    LabelValue(label = "运行时长", value = formatUptime(state.health.uptimeSeconds))
                    LabelValue(label = "最近重载", value = state.health.lastReloadAt)
                    LabelValue(label = "CDN", value = state.health.cdnServer)
                    LabelValue(
                        label = "Reload Token",
                        value = if (state.health.reloadTokenConfigured) "已配置" else "未配置",
                    )
                    if (state.health.message.isNotBlank()) {
                        Text(
                            text = state.health.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatUptime(seconds: Long): String {
    if (seconds <= 0) {
        return "-"
    }

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainSeconds = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, remainSeconds)
}
