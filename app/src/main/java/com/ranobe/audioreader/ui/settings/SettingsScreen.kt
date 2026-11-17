package com.ranobe.audioreader.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ranobe.audioreader.data.settings.AppSettings
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.util.BackgroundPermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenAuth: (String) -> Unit,
    onModels: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var settings by remember(container) { mutableStateOf<AppSettings?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var notifications by remember(context) { mutableStateOf(notificationsAllowed(context)) }
    var battery by remember(context) {
        mutableStateOf(BackgroundPermissionHelper.isIgnoringBatteryOptimizations(context))
    }
    var ranobeLibSignedIn by remember(container) {
        mutableStateOf(!container.authStore.ranobeLibToken.isNullOrBlank())
    }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun save(transform: (AppSettings) -> AppSettings) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { container.settingsRepository.update(transform) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("Не удалось сохранить настройки. Повторите попытку.")
            } finally {
                saving = false
            }
        }
    }

    fun openSystemSettings(action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify("Не удалось открыть системные настройки. Откройте их вручную.")
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notifications = notificationsAllowed(context)
        if (!notifications) notify("Уведомления выключены. Их можно разрешить в настройках приложения.")
    }

    DisposableEffect(lifecycleOwner, context, container) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = notificationsAllowed(context)
                battery = BackgroundPermissionHelper.isIgnoringBatteryOptimizations(context)
                ranobeLibSignedIn = !container.authStore.ranobeLibToken.isNullOrBlank()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(container, lifecycleOwner, retry) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loadError = null
            try {
                container.settingsRepository.flow.collect {
                    settings = it
                    loadError = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = "Не удалось прочитать настройки."
                snackbar.showSnackbar("Не удалось прочитать настройки.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadError != null) item {
                SettingsSection("Ошибка загрузки") {
                    Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("Повторить") }
                }
            }
            val value = settings
            if (value == null && loadError == null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Загружаем настройки…")
                }
            }
            if (value != null) {
                val editable = !saving && loadError == null
                item {
                    SettingsSection("Озвучка") {
                        Text("Активная модель: ${value.modelId}")
                        Text("Голос: ${value.speaker}")
                        OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                            Text("Выбрать модель и голос")
                        }
                        Text("Изменения качества и словаря применяются при новой озвучке. Готовое аудио не изменится.", style = MaterialTheme.typography.bodySmall)
                        NumberChoice(
                            title = "Частота дискретизации",
                            value = value.sampleRate,
                            choices = listOf(24000, 48000),
                            enabled = editable,
                            label = { "${it / 1000} кГц" },
                            onSelect = { rate -> save { it.copy(sampleRate = rate) } },
                        )
                        NumberChoice(
                            title = "Битрейт аудио",
                            value = value.bitrate,
                            choices = listOf(32000, 48000, 64000, 96000),
                            enabled = editable,
                            label = { "${it / 1000} кбит/с" },
                            onSelect = { rate -> save { it.copy(bitrate = rate) } },
                        )
                        SettingsSwitch("Только по Wi-Fi", "Сетевые фоновые задачи без мобильного интернета", value.wifiOnly, editable) {
                            checked -> save { it.copy(wifiOnly = checked) }
                        }
                        SettingsSwitch("Только при зарядке", "Выполнять фоновую озвучку при подключённом питании", value.chargingOnly, editable) {
                            checked -> save { it.copy(chargingOnly = checked) }
                        }
                    }
                }
                item {
                    SettingsSection("Чтение") {
                        SettingsSwitch("Тёмная тема", null, value.darkTheme, editable) { checked -> save { it.copy(darkTheme = checked) } }
                        TextSizeSetting(value.textSize, editable) { size -> save { it.copy(textSize = size) } }
                        SettingsSwitch("Автопрокрутка", "Следовать за озвучиваемым текстом", value.autoScroll, editable) { checked -> save { it.copy(autoScroll = checked) } }
                        SettingsSwitch("Возврат на 5 секунд", "При возобновлении воспроизведения", value.rewindSeconds == 5, editable) { checked ->
                            save { it.copy(rewindSeconds = if (checked) 5 else 0) }
                        }
                    }
                }
                item {
                    SettingsSection("Автоочистка") {
                        Text("Удалять прослушанное аудио через указанное число часов. Текст глав и модели сохраняются.")
                        CleanupSetting(value.cleanupAfterHours, editable) { hours -> save { it.copy(cleanupAfterHours = hours) } }
                    }
                }
            }
            item {
                SettingsSection("Работа в фоне") {
                    Text("Уведомления: ${if (notifications) "разрешены" else "выключены"}")
                    OutlinedButton(
                        onClick = {
                            openSystemSettings {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !BackgroundPermissionHelper.notificationsGranted(context) &&
                                    settings?.notificationsPrompted == false
                                ) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    save { it.copy(notificationsPrompted = true) }
                                } else {
                                    BackgroundPermissionHelper.openAppDetailsSettings(context)
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (notifications) "Настройки уведомлений" else "Разрешить уведомления") }
                    Text("Оптимизация батареи: ${if (battery) "приложение исключено" else "может ограничивать работу"}")
                    OutlinedButton(
                        onClick = {
                            openSystemSettings {
                                val activity = context.findActivity()
                                if (activity != null && !battery) {
                                    if (!BackgroundPermissionHelper.requestIgnoreBatteryOptimizations(activity)) {
                                        notify("Откройте раздел батареи и выберите «Без ограничений».")
                                    }
                                } else BackgroundPermissionHelper.openAppDetailsSettings(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Настроить работу от батареи") }
                    Text("Автозапуск: Проверьте вручную")
                    Text("Android не позволяет достоверно проверить разрешение автозапуска.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            openSystemSettings {
                                if (!BackgroundPermissionHelper.openAutostartSettings(context)) {
                                    notify("Раздел автозапуска не найден. Проверьте его в системных настройках вручную.")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Открыть настройки автозапуска") }
                    Text("На Vivo и iQOO выберите для приложения «Без ограничений» в настройках батареи и разрешите автозапуск. Названия разделов зависят от версии системы.")
                    TextButton(onClick = { openSystemSettings { BackgroundPermissionHelper.openAppDetailsSettings(context) } }) {
                        Text("Системные настройки приложения")
                    }
                }
            }
            item {
                SettingsSection("Источники и вход") {
                    if (container.sources.isEmpty()) Text("Источники пока не подключены.")
                    container.sources.forEach { source ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(source.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (source.id == "ranobelib") {
                                    if (ranobeLibSignedIn) "Данные входа сохранены. Срок действия проверяется источником."
                                    else "Сохранённых данных входа нет."
                                } else if (source.requiresAuth) "Требуется вход. Состояние сессии проверяйте на сайте."
                                else "Вход при необходимости. Состояние сессии проверяйте на сайте.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { onOpenAuth(source.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Открыть вход: ${source.name}")
                            }
                            if (source.id == "ranobelib" && ranobeLibSignedIn) {
                                TextButton(onClick = { logoutConfirmation = true }, enabled = !saving) {
                                    Text("Сбросить вход RanobeLib в приложении")
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection("О приложении и лицензиях") {
                    Text("Для личного некоммерческого использования. Соблюдайте права авторов книг и условия источников.")
                    Text("Лицензии голосовых паков различаются. Пак русских голосов Silero распространяется по CC BY-NC-SA 4.0 с ограничением на коммерческое использование; дополнительные голоса — по MIT. Условия выбранного пака проверяйте перед использованием.")
                    TextButton(onClick = onModels) { Text("Голосовые паки и лицензии") }
                    Text("Лицензионные файлы в ресурсах приложения:", style = MaterialTheme.typography.titleSmall)
                    Text("RuVoice.LICENSE.txt\nRuVoice.NOTICE.txt\ncldr.LICENSE.txt", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (logoutConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!saving) logoutConfirmation = false },
            title = { Text("Сбросить вход RanobeLib?") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("Будут удалены только токен и идентификатор RanobeLib, сохранённые приложением. Cookies и сессия сайта останутся; для полного выхода откройте сайт. Вход в другие источники не изменится.")
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { container.authStore.clearRanobeLib() }
                            ranobeLibSignedIn = false
                            logoutConfirmation = false
                            notify("Сохранённый вход RanobeLib сброшен.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notify("Не удалось сбросить вход RanobeLib.")
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("Сбросить вход") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { logoutConfirmation = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NumberChoice(title: String, value: Int, choices: List<Int>, enabled: Boolean, label: (Int) -> String, onSelect: (Int) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$title: ${label(value)}")
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    TextButton(onClick = { choosing = false; onSelect(choice) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(label(choice) + if (choice == value) " · выбрано" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Отмена") } },
    )
}

@Composable
private fun TextSizeSetting(value: Int, enabled: Boolean, onSave: (Int) -> Unit) {
    var draft by rememberSaveable(value) { mutableIntStateOf(value) }
    Text("Размер текста: $draft")
    Slider(
        value = draft.toFloat(),
        onValueChange = { draft = it.roundToInt() },
        onValueChangeFinished = { if (draft != value) onSave(draft) },
        valueRange = 14f..32f,
        steps = 17,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Так будет выглядеть текст книги.", fontSize = draft.sp)
    if (draft != value) TextButton(onClick = { onSave(draft) }, enabled = enabled) { Text("Сохранить размер") }
}

@Composable
private fun CleanupSetting(value: Int, enabled: Boolean, onSave: (Int) -> Unit) {
    var draft by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val hours = draft.toIntOrNull()
    val valid = draft.isNotEmpty() && draft.all { it in '0'..'9' } && hours != null && hours in 0..8760
    Text(if (value == 0) "Сейчас автоочистка выключена" else "Сейчас: через $value ч после прослушивания")
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.take(6) },
        label = { Text("Часы до удаления") },
        supportingText = { Text(if (valid) "От 0 до 8760. 0 — выключено. Нажмите «Сохранить»." else "Введите целое число от 0 до 8760.") },
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = { draft = "0" }, enabled = enabled && draft != "0") { Text("Выключить (0 часов)") }
    Button(onClick = { hours?.let(onSave) }, enabled = enabled && valid && hours != value, modifier = Modifier.fillMaxWidth()) {
        Text("Сохранить автоочистку")
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    BackgroundPermissionHelper.notificationsGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
