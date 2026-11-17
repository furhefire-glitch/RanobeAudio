package com.ranobe.audioreader.domain.text

import com.ranobe.audioreader.data.local.entity.ReplacementRuleEntity
import org.junit.Assert.*
import org.junit.Test

class StressDictionaryTest {
    @Test fun validatesStressBeforeVowel() {
        assertTrue(StressDictionary.isStress("замок", "зам+ок"))
        assertTrue(StressDictionary.isStress("Замок", "з+амок"))
        assertFalse(StressDictionary.isStress("замок", "за+мок"))
        assertFalse(StressDictionary.isStress("замок", "замок+"))
        assertFalse(StressDictionary.isStress("замок", "з+ам+ок"))
        assertFalse(StressDictionary.isStress("замок", "д+ом"))
    }

    @Test fun separatesPronunciationFromTextReplacement() {
        val snapshot = DictionarySnapshot(listOf(
            ReplacementRuleEntity(pattern = "Замок", replacement = "зам+ок"),
            ReplacementRuleEntity(pattern = "ГГ", replacement = "главный герой"),
            ReplacementRuleEntity(pattern = "\\[.*?]", replacement = "", isRegex = true),
        ))
        assertEquals(mapOf("замок" to "зам+ок"), snapshot.userDict)
        assertEquals("Где Замок? главный герой ", snapshot.applyReplacements("Где Замок? ГГ [сноска]"))
    }

    @Test fun replacementGroupsAndLiteralDollarKeepTheirMeaning() {
        val regex = DictionarySnapshot(listOf(ReplacementRuleEntity(pattern = "(день) (два)", replacement = "\$2 \$1", isRegex = true)))
        assertEquals("два день", regex.applyReplacements("день два"))
        val literal = DictionarySnapshot(listOf(ReplacementRuleEntity(pattern = "сто", replacement = "\$5\\")))
        assertEquals("\$5\\ рублей", literal.applyReplacements("сто рублей"))
    }

    @Test(timeout = 5000, expected = IllegalArgumentException::class)
    fun pathologicalRegexCannotHoldAudioQueueForever() {
        DictionarySnapshot(listOf(ReplacementRuleEntity(pattern = "(a+)+$", replacement = "x", isRegex = true)))
            .applyReplacements("a".repeat(10000) + "!")
    }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveReplacementGrowthIsRejected() {
        DictionarySnapshot(listOf(ReplacementRuleEntity(pattern = "a", replacement = "x".repeat(512))))
            .applyReplacements("a".repeat(5000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRegexIsNotSilentlyIgnored() {
        DictionarySnapshot(listOf(ReplacementRuleEntity(pattern = "[", replacement = "", isRegex = true))).applyReplacements("Текст")
    }
}
