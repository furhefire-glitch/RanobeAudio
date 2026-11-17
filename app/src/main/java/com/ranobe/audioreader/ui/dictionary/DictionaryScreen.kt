package com.ranobe.audioreader.ui.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import com.ranobe.audioreader.data.local.entity.ReplacementRuleEntity
import com.ranobe.audioreader.di.AppContainer
import com.ranobe.audioreader.domain.text.StressDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class DictionaryRow(val rule: ReplacementRuleEntity, val mode: RuleMode)
private data class EditorTarget(val rule: ReplacementRuleEntity?, val mode: RuleMode)
private data class DeleteTarget(val rule: ReplacementRuleEntity? = null)
private class RuleEditException(message: String) : IllegalArgumentException(message)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var rows by remember(container) { mutableStateOf<List<DictionaryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DeleteTarget?>(null) }

    LaunchedEffect(container, lifecycleOwner, retry) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            loadError = null
            try {
                container.dictionaryDao.observeAll().collect { rules ->
                    rows = withContext(Dispatchers.Default) {
                        rules.sortedWith(ruleOrder).map { rule ->
                            DictionaryRow(rule, when {
                                rule.isRegex -> RuleMode.REGEX
                                StressDictionary.isStress(rule.pattern, rule.replacement) -> RuleMode.STRESS
                                else -> RuleMode.LITERAL
                            })
                        }
                    }
                    loading = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                loadError = "Не удалось загрузить словарь."
                snackbar.showSnackbar("Не удалось загрузить словарь.")
            }
        }
    }

    fun mutate(message: String, operation: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        mutationError = null
        scope.launch {
            var resultMessage = message
            try {
                withContext(Dispatchers.IO) { operation() }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultMessage = if (e is RuleEditException) e.message.orEmpty() else "Не удалось сохранить изменения словаря. Повторите попытку."
                mutationError = resultMessage
            } finally {
                busy = false
            }
            snackbar.showSnackbar(resultMessage)
        }
    }

    val visibleRows = remember(rows, search) {
        val query = search.trim()
        rows.filter { query.isEmpty() || it.rule.pattern.contains(query, ignoreCase = true) || it.rule.replacement.contains(query, ignoreCase = true) }
    }
    val positions = remember(rows) { rows.mapIndexed { index, row -> row.rule.id to index }.toMap() }
    val editable = !loading && loadError == null && !busy

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Словарь озвучки") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Замены и ударения применяются при новой озвучке. Уже созданное аудио не меняется.")
            }
            item {
                Button(
                    onClick = { mutationError = null; editor = EditorTarget(null, RuleMode.LITERAL) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить правило") }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(256) },
                    label = { Text("Поиск по шаблону и замене") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Показано: ${visibleRows.size} из ${rows.size}", style = MaterialTheme.typography.labelLarge)
                    Text("Правила выполняются сверху вниз. Стрелки меняют общий порядок, включая скрытые поиском записи.", style = MaterialTheme.typography.bodySmall)
                    if (rows.isNotEmpty()) TextButton(onClick = { mutationError = null; deleting = DeleteTarget() }, enabled = editable) {
                        Text("Удалить весь словарь", color = MaterialTheme.colorScheme.error)
                    }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (loading) item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Загружаем словарь…")
                }
            }
            if (loadError != null) item {
                Column {
                    Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("Повторить") }
                }
            }
            if (!loading && loadError == null && visibleRows.isEmpty()) item {
                Text(if (rows.isEmpty()) "Словарь пуст. Добавьте замену, выражение или ударение." else "По этому запросу ничего не найдено.")
            }
            items(visibleRows, key = { it.rule.id }) { row ->
                val rule = row.rule
                val position = positions[rule.id] ?: 0
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${position + 1}. ${row.mode.title}", style = MaterialTheme.typography.labelLarge)
                        Text(rule.pattern, style = MaterialTheme.typography.titleMedium)
                        Text(if (rule.replacement.isEmpty()) "Замена: пустая строка (удаление совпадения)" else "Замена: ${rule.replacement}")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (rule.enabled) "Включено" else "Выключено", modifier = Modifier.weight(1f))
                            Switch(
                                checked = rule.enabled,
                                enabled = editable,
                                modifier = Modifier.semantics { contentDescription = "Включить правило ${rule.pattern}" },
                                onCheckedChange = { enabled ->
                                    mutate("Состояние правила сохранено.", operation = {
                                        container.database.withTransaction {
                                            val current = orderedRules(container)
                                            val affected = current.find { it.id == rule.id } ?: throw RuleEditException("Правило уже удалено. Обновите список.")
                                            if (enabled && affected.isRegex) {
                                                val validation = validateRule(RuleDraft(RuleMode.REGEX, affected.pattern, affected.replacement, true, ""))
                                                validation.error?.let { throw RuleEditException(it) }
                                            }
                                            writeOrder(container, current.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                                        }
                                    })
                                },
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(enabled = editable && position > 0, onClick = {
                                mutate("Порядок правил сохранён.", operation = { moveRule(container, rule.id, -1) })
                            }) { Icon(Icons.Default.ArrowUpward, contentDescription = "Переместить правило выше") }
                            IconButton(enabled = editable && position < rows.lastIndex, onClick = {
                                mutate("Порядок правил сохранён.", operation = { moveRule(container, rule.id, 1) })
                            }) { Icon(Icons.Default.ArrowDownward, contentDescription = "Переместить правило ниже") }
                            IconButton(enabled = editable, onClick = { mutationError = null; editor = EditorTarget(rule, row.mode) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Изменить правило")
                            }
                            IconButton(enabled = editable, onClick = { mutationError = null; deleting = DeleteTarget(rule) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить правило")
                            }
                        }
                    }
                }
            }
        }
    }

    editor?.let { target ->
        RuleEditor(
            target = target,
            saving = busy,
            saveError = mutationError,
            onDismiss = { if (!busy) editor = null },
            onSave = { draft ->
                mutate("Правило сохранено. Оно будет учтено при новой озвучке.", operation = {
                    val validation = validateRule(draft)
                    validation.error?.let { throw RuleEditException(it) }
                    saveRule(container, target.rule?.id, draft)
                }, onSuccess = { editor = null })
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(if (target.rule == null) "Удалить весь словарь?" else "Удалить правило?") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(if (target.rule == null) "Будут удалены все замены и ударения, а не только результаты поиска. Отменить удаление нельзя. Текст глав и готовое аудио останутся." else "Будет удалено правило «${target.rule.pattern}». Отменить удаление нельзя. Готовое аудио не изменится.")
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    mutate("Словарь обновлён.", operation = {
                        container.database.withTransaction {
                            if (target.rule == null) container.dictionaryDao.deleteAll()
                            else {
                                val current = orderedRules(container)
                                current.find { it.id == target.rule.id }?.let { container.dictionaryDao.delete(it) }
                                writeOrder(container, current.filterNot { it.id == target.rule.id })
                            }
                        }
                    }, onSuccess = { deleting = null })
                }) { Text(if (target.rule == null) "Удалить всё" else "Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RuleEditor(target: EditorTarget, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (RuleDraft) -> Unit) {
    var mode by rememberSaveable(target.rule?.id) { mutableStateOf(target.mode) }
    var pattern by rememberSaveable(target.rule?.id) { mutableStateOf(target.rule?.pattern.orEmpty()) }
    var replacement by rememberSaveable(target.rule?.id) { mutableStateOf(target.rule?.replacement.orEmpty()) }
    var enabled by rememberSaveable(target.rule?.id) { mutableStateOf(target.rule?.enabled ?: true) }
    var testText by rememberSaveable(target.rule?.id) { mutableStateOf("") }
    var checkedDraft by remember { mutableStateOf<RuleDraft?>(null) }
    var validation by remember { mutableStateOf<RuleValidation?>(null) }
    val draft = RuleDraft(mode, pattern, replacement, enabled, testText)
    LaunchedEffect(draft) {
        validation = null
        checkedDraft = null
        delay(300)
        validation = validateRule(draft)
        checkedDraft = draft
    }
    val result = validation.takeIf { checkedDraft == draft }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (target.rule == null) "Новое правило" else "Изменить правило") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RuleMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = mode == option, enabled = !saving, role = Role.RadioButton, onClick = { mode = option }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = mode == option, onClick = null, enabled = !saving)
                        Text(option.title)
                    }
                }
                Text(when (mode) {
                    RuleMode.LITERAL -> "Точная замена с учётом регистра. Пустая замена удаляет найденный текст."
                    RuleMode.REGEX -> "Синтаксис Java Regex. В замене доступны \$1 и \${имя}; буквальные \$ и обратную косую черту экранируйте. Пустая замена удаляет совпадения."
                    RuleMode.STRESS -> "Одно слово без пробелов. В замене поставьте один + перед ударной гласной: молоко → молок+о. Запись сохраняется в нижнем регистре."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.take(MAX_RULE_PATTERN) },
                    label = { Text(if (mode == RuleMode.STRESS) "Слово" else "Шаблон") },
                    supportingText = { Text("${pattern.length}/$MAX_RULE_PATTERN; не может быть пустым") },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it.take(MAX_RULE_REPLACEMENT) },
                    label = { Text(if (mode == RuleMode.STRESS) "Слово с ударением" else "Замена") },
                    supportingText = { Text("${replacement.length}/$MAX_RULE_REPLACEMENT") },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Правило включено", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "Правило включено" })
                }
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it.take(MAX_TEST_TEXT) },
                    label = { Text("Текст для проверки") },
                    supportingText = { Text("${testText.length}/$MAX_TEST_TEXT символов") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    minLines = 2,
                    maxLines = 5,
                )
                if (result == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Проверяем правило…", style = MaterialTheme.typography.bodySmall)
                } else if (result.error != null) {
                    Text(result.error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Предпросмотр", style = MaterialTheme.typography.titleSmall)
                    if (testText.isEmpty()) Text("Введите пример для проверки результата. Синтаксис шаблона и замены уже проверен.")
                    else SelectionContainer { Text(result.preview.ifEmpty { "Пустая строка — весь пример удалён." }) }
                    if (result.shortened) Text("Результат сокращён до 4000 символов.", style = MaterialTheme.typography.bodySmall)
                    if (mode == RuleMode.STRESS) Text("Это пример расстановки +, а не синтез речи. Ударение применяется движком при новой озвучке.", style = MaterialTheme.typography.bodySmall)
                    Text("Проверяется только это правило, без остальных записей словаря.", style = MaterialTheme.typography.bodySmall)
                }
                if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error)
                if (saving) Text("Сохраняем…")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = !saving && result != null && result.error == null) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } },
    )
}

private val ruleOrder = compareBy<ReplacementRuleEntity> { it.sortOrder }.thenBy { it.id }

private suspend fun orderedRules(container: AppContainer): List<ReplacementRuleEntity> =
    container.dictionaryDao.observeAll().first().sortedWith(ruleOrder)

private suspend fun writeOrder(container: AppContainer, rules: List<ReplacementRuleEntity>) {
    rules.forEachIndexed { index, rule -> container.dictionaryDao.update(rule.copy(sortOrder = index)) }
}

private suspend fun moveRule(container: AppContainer, id: Long, direction: Int) {
    container.database.withTransaction {
        val rules = orderedRules(container).toMutableList()
        val from = rules.indexOfFirst { it.id == id }
        if (from < 0) throw RuleEditException("Правило уже удалено. Обновите список.")
        val to = from + direction
        if (to in rules.indices) {
            val moved = rules.removeAt(from)
            rules.add(to, moved)
        }
        writeOrder(container, rules)
    }
}

private suspend fun saveRule(container: AppContainer, id: Long?, draft: RuleDraft) {
    container.database.withTransaction {
        val before = orderedRules(container)
        val old = id?.let { key -> before.find { it.id == key } ?: throw RuleEditException("Правило уже удалено. Закройте редактор и добавьте новое.") }
        val position = old?.let { before.indexOf(it) } ?: before.size
        val saved = if (draft.mode == RuleMode.STRESS) {
            val word = draft.pattern.trim().lowercase(Locale.ROOT)
            val accented = draft.replacement.trim().lowercase(Locale.ROOT)
            if (before.any { it.id != id && !it.isRegex && it.pattern.equals(word, ignoreCase = true) }) {
                throw RuleEditException("Для этого слова уже есть правило. Отредактируйте его, чтобы не заменить другую запись без подтверждения.")
            }
            container.stressDictionary.saveStress(word, accented)
            val inserted = orderedRules(container).single { !it.isRegex && it.pattern == word }
            if (old != null && old.id != inserted.id) container.dictionaryDao.delete(old)
            inserted.copy(enabled = draft.enabled)
        } else {
            val entity = ReplacementRuleEntity(
                id = old?.id ?: 0L,
                pattern = draft.pattern,
                replacement = draft.replacement,
                isRegex = draft.mode == RuleMode.REGEX,
                enabled = draft.enabled,
                sortOrder = position,
            )
            if (old == null) entity.copy(id = container.dictionaryDao.insert(entity))
            else entity.also { container.dictionaryDao.update(it) }
        }
        val reordered = before.filterNot { it.id == old?.id || it.id == saved.id }.toMutableList()
        reordered.add(position.coerceAtMost(reordered.size), saved)
        writeOrder(container, reordered)
    }
}
