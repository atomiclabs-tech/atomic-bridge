package tech.atomiclabs.link.core.screen

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.Bounds
import tech.atomiclabs.link.core.port.UiNodeProperties

class TextMatchTest {
    @Test fun compatibilityCharactersAndCombiningFormsNormalizeBeforeComparison() {
        assertEquals("°c", TextMatch.normalize("℃"))
        assertTrue(TextMatch.contains("Temperature: ℃", "°C"))
        assertTrue(TextMatch.equals("ＡＢＣ ①", "abc 1"))
        assertTrue(TextMatch.equals("cafe\u0301", "CAFÉ"))
    }

    @Test fun whitespaceClassesCollapseAndTrimToOneSpace() {
        for (space in listOf(" ", "\t", "\n", "\r", "\u000b", "\u000c", "\u00a0", "\u2007", "\u202f")) {
            assertEquals("me at the zoo", TextMatch.normalize("${space}Me${space}${space}at the zoo$space"))
        }
        assertTrue(TextMatch.contains("Me\u00a0at the zoo", "at the zoo"))
        assertTrue(TextMatch.equals("Me\u00a0at\u2007the\u202fzoo", "me at the zoo"))
    }

    @Test fun caseFoldingUsesRootEvenWhenTheDefaultLocaleIsTurkish() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i", TextMatch.normalize("I"))
            assertTrue(TextMatch.equals("I", "i"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun emptyNormalizedNeedlesNeverMatchIncludingEmptyOrMissingHaystacks() {
        for (needle in listOf("", " ", "\t\n", "\u00a0\u2007\u202f")) {
            for (haystack in listOf(null, "", "visible")) {
                assertFalse(TextMatch.contains(haystack, needle))
                assertFalse(TextMatch.equals(haystack, needle))
            }
        }
        assertFalse(TextMatch.contains(null, "visible"))
    }

    @Test fun containsAndExactComparisonsKeepTheirDifferentMeanings() {
        assertTrue(TextMatch.contains(" Hello\u00a0WORLD ", "hello"))
        assertFalse(TextMatch.equals("Hello world", "hello"))
        assertTrue(TextMatch.equals(" Hello\u00a0WORLD ", "hello world"))
    }

    @Test fun matchesNodeUsesTextThenContentDescriptionAndNeverHintText() {
        val properties = UiNodeProperties(Bounds(0, 0, 10, 10), 1,
            text = "℃", contentDescription = "Dismiss", hintText = "Hint only")
        assertTrue(TextMatch.matchesNode(properties, "°C", exact = true))
        assertTrue(TextMatch.matchesNode(properties, "dismiss", exact = true))
        assertTrue(TextMatch.matchesNode(properties.copy(text = ""), "dismiss"))
        assertFalse(TextMatch.matchesNode(properties, "hint only"))
        assertFalse(TextMatch.matchesNode(properties, ""))
    }
}
