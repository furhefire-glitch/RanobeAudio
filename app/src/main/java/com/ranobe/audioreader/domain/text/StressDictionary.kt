package com.ranobe.audioreader.domain.text

import com.ranobe.audioreader.data.local.dao.DictionaryDao
import com.ranobe.audioreader.data.local.entity.ReplacementRuleEntity
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class StressDictionary(private val dao: DictionaryDao) {
    suspend fun snapshot() = DictionarySnapshot(dao.getEnabled())

    suspend fun saveStress(word: String, accented: String) {
        val key = word.lowercase(Locale.ROOT)
        val value = accented.trim().lowercase(Locale.ROOT)
        require(isStress(key, value)) { "Поставьте один + перед ударной гласной, не меняя слово" }
        dao.saveStress(key, value)
    }

    companion object {
        private val wordPattern = Regex("[\\p{L}]+(?:[-’'][\\p{L}]+)*")
        private const val VOWELS = "аеёиоуыэюяіїєaeiouy"

        fun isStress(word: String, accented: String): Boolean {
            if (!wordPattern.matches(word) || accented.count { it == '+' } != 1) return false
            val index = accented.indexOf('+')
            return accented.getOrNull(index + 1)?.lowercaseChar()?.let { it in VOWELS } == true &&
                accented.replace("+", "").equals(word, ignoreCase = true)
        }
    }
}

class DictionarySnapshot(private val rules: List<ReplacementRuleEntity>) {
    val userDict: Map<String, String> = rules.filter {
        !it.isRegex && StressDictionary.isStress(it.pattern, it.replacement)
    }.associate { it.pattern.lowercase(Locale.ROOT) to it.replacement.lowercase(Locale.ROOT) }

    fun applyReplacements(text: String): String {
        var result = text
        rules.forEach { rule ->
            require(rule.pattern.isNotEmpty()) { "Пустой шаблон в словаре" }
            if (rule.isRegex || !StressDictionary.isStress(rule.pattern, rule.replacement)) {
                val pattern = if (rule.isRegex) rule.pattern else Pattern.quote(rule.pattern)
                val replacement = if (rule.isRegex) rule.replacement else Matcher.quoteReplacement(rule.replacement)
                val output = StringBuffer()
                try {
                    val matcher = Pattern.compile(pattern).matcher(BoundedRuleText(result))
                    while (matcher.find()) {
                        matcher.appendReplacement(output, replacement)
                        require(output.length <= 2_000_000) { "Словарь увеличил текст главы сверх 2 млн символов" }
                    }
                    matcher.appendTail(output)
                } catch (error: StackOverflowError) {
                    throw IllegalArgumentException("Слишком сложное выражение в словаре", error)
                }
                require(output.length <= 2_000_000) { "Словарь увеличил текст главы сверх 2 млн символов" }
                result = output.toString()
            }
        }
        return result
    }
}

private class BoundedRuleText(private val text: String, private val deadline: Long = System.nanoTime() + 2_000_000_000L) : CharSequence {
    private var reads = 0L
    override val length get() = text.length
    override fun get(index: Int): Char {
        reads++
        require(reads <= 20_000_000 && (reads % 1024 != 0L || System.nanoTime() <= deadline)) {
            "Выражение в словаре слишком сложное: упростите шаблон"
        }
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BoundedRuleText(text.substring(startIndex, endIndex), deadline)
    override fun toString(): String = text
}
