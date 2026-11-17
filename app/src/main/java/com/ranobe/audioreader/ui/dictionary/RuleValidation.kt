package com.ranobe.audioreader.ui.dictionary

import com.ranobe.audioreader.domain.text.StressDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.CoroutineContext

internal const val MAX_RULE_PATTERN = 256
internal const val MAX_RULE_REPLACEMENT = 512
internal const val MAX_TEST_TEXT = 512
private const val MAX_PREVIEW_OUTPUT = 4000

internal enum class RuleMode(val title: String) {
    LITERAL("Обычная замена"),
    REGEX("Регулярное выражение"),
    STRESS("Ударение"),
}

internal data class RuleDraft(
    val mode: RuleMode,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean,
    val testText: String,
)

internal data class RuleValidation(val error: String? = null, val preview: String = "", val shortened: Boolean = false)

internal suspend fun validateRule(draft: RuleDraft): RuleValidation = withContext(Dispatchers.Default) {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (draft.pattern.isEmpty()) return@withContext RuleValidation(error = "Шаблон не может быть пустым.")
    if (draft.pattern.length > MAX_RULE_PATTERN) return@withContext RuleValidation(error = "Шаблон: не более $MAX_RULE_PATTERN символов.")
    if (draft.replacement.length > MAX_RULE_REPLACEMENT) return@withContext RuleValidation(error = "Замена: не более $MAX_RULE_REPLACEMENT символов.")
    if (draft.testText.length > MAX_TEST_TEXT) return@withContext RuleValidation(error = "Пример: не более $MAX_TEST_TEXT символов.")
    try {
        val input = PreviewInput(draft.testText, context)
        val output = when (draft.mode) {
            RuleMode.LITERAL -> {
                if (StressDictionary.isStress(draft.pattern, draft.replacement)) {
                    return@withContext RuleValidation(error = "Эта запись задаёт ударение. Выберите режим «Ударение», чтобы сохранить её в словарь ударений.")
                }
                draft.testText.replace(draft.pattern, draft.replacement)
            }
            RuleMode.STRESS -> {
                val word = draft.pattern.trim().lowercase(Locale.ROOT)
                val accented = draft.replacement.trim().lowercase(Locale.ROOT)
                if (!StressDictionary.isStress(word, accented)) {
                    return@withContext RuleValidation(error = "Введите слово и то же слово с одним + перед ударной гласной, например: молоко → молок+о.")
                }
                val pattern = Pattern.compile("(?iu)(?<![\\p{L}])${Pattern.quote(word)}(?![\\p{L}])")
                pattern.matcher(input).replaceAll(Matcher.quoteReplacement(accented))
            }
            RuleMode.REGEX -> {
                val pattern = Pattern.compile(draft.pattern)
                // An empty alternative validates replacement groups even when the example has no match.
                try {
                    Pattern.compile("(?:)|${draft.pattern}").matcher("").replaceFirst(draft.replacement)
                } catch (e: IllegalArgumentException) {
                    return@withContext RuleValidation(error = "Недопустимая замена: проверьте ссылки \$1, \${имя} и экранирование обратной косой чертой.")
                } catch (e: IndexOutOfBoundsException) {
                    return@withContext RuleValidation(error = "В замене указана несуществующая группа. Проверьте ссылки \$1, \$2 и другие.")
                }
                if (draft.testText.isEmpty()) "" else pattern.matcher(input).replaceAll(draft.replacement)
            }
        }
        context.ensureActive()
        RuleValidation(preview = output.take(MAX_PREVIEW_OUTPUT), shortened = output.length > MAX_PREVIEW_OUTPUT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PatternSyntaxException) {
        RuleValidation(error = "Ошибка регулярного выражения${if (e.index >= 0) " около позиции ${e.index + 1}" else ""}. Проверьте скобки, группы и экранирование.")
    } catch (e: PreviewLimitException) {
        RuleValidation(error = "Предпросмотр слишком сложный. Упростите выражение или сократите пример.")
    } catch (e: StackOverflowError) {
        RuleValidation(error = "Слишком глубокое регулярное выражение. Упростите шаблон.")
    } catch (e: Exception) {
        RuleValidation(error = "Не удалось проверить правило. Проверьте шаблон и замену.")
    }
}

private class PreviewLimitException : RuntimeException()

// Java regex ignores coroutine cancellation unless its CharSequence checks it.
private class PreviewInput(
    private val text: String,
    private val context: CoroutineContext,
    private val deadline: Long = System.nanoTime() + 200_000_000L,
) : CharSequence {
    private var reads = 0
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        context.ensureActive()
        if (++reads > 250_000 || System.nanoTime() > deadline) throw PreviewLimitException()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        context.ensureActive()
        return PreviewInput(text.substring(startIndex, endIndex), context, deadline)
    }
    override fun toString(): String = text
}
