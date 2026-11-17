package com.ranobe.audioreader.ui.dictionary

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuleValidationTest {
    @Test fun literalReplacementKeepsDollarAndBackslash() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.LITERAL, "монет", "\$5\\", true, "10 монет"))
        assertNull(result.error)
        assertEquals("10 \$5\\", result.preview)
    }

    @Test fun validatesGroupsEvenWhenExampleDoesNotMatch() = runBlocking {
        for (replacement in listOf("\$2", "\${missing}", "\\")) {
            val result = validateRule(RuleDraft(RuleMode.REGEX, "(кот)", replacement, true, "собака"))
            assertNotNull(replacement, result.error)
        }
    }

    @Test fun numberedAndNamedGroupsProducePreview() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.REGEX, "(?<word>кот)(ик)?", "\${word}-\$2", true, "котик"))
        assertNull(result.error)
        assertEquals("кот-ик", result.preview)
    }

    @Test fun malformedPatternIsReported() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.REGEX, "[", "", true, "текст"))
        assertNotNull(result.error)
    }

    @Test fun stressPreviewUsesWholeWordsOnly() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.STRESS, "молоко", "молок+о", true, "Молоко и молоковоз"))
        assertNull(result.error)
        assertEquals("молок+о и молоковоз", result.preview)
        assertNotNull(validateRule(RuleDraft(RuleMode.LITERAL, "молоко", "молок+о", true, "молоко")).error)
    }

    @Test fun invalidStressIsRejected() = runBlocking {
        for (replacement in listOf("мол+ко", "м+олок+о", "молок+а", "молоко")) {
            assertNotNull(validateRule(RuleDraft(RuleMode.STRESS, "молоко", replacement, true, "молоко")).error)
        }
    }

    @Test(timeout = 5000) fun pathologicalRegexPreviewIsBounded() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.REGEX, "(a+)+$", "b", true, "a".repeat(500) + "!"))
        assertNotNull(result.error)
    }

    @Test fun longPreviewIsShortenedWithoutRejectingRule() = runBlocking {
        val result = validateRule(RuleDraft(RuleMode.LITERAL, "a", "b".repeat(512), true, "a".repeat(512)))
        assertNull(result.error)
        assertTrue(result.shortened)
        assertEquals(4000, result.preview.length)
    }
}
