package tech.atomiclabs.link.core.screen

import java.text.Normalizer
import java.util.Locale
import tech.atomiclabs.link.core.port.UiNodeProperties

/** Shared text comparisons keep searches consistent without treating hints as visible labels. */
object TextMatch {
    private val whitespace = Regex("[\\s\u00a0\u2007\u202f]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(whitespace, " ").trim().lowercase(Locale.ROOT)

    fun contains(haystack: String?, needle: String): Boolean = matches(haystack, needle, false)

    fun equals(haystack: String?, needle: String): Boolean = matches(haystack, needle, true)

    fun matchesNode(properties: UiNodeProperties, needle: String, exact: Boolean = false): Boolean =
        matches(properties.text, needle, exact) || matches(properties.contentDescription, needle, exact)

    private fun matches(haystack: String?, needle: String, exact: Boolean): Boolean {
        val query = normalize(needle)
        if (query.isEmpty() || haystack == null) return false
        val text = normalize(haystack)
        return if (exact) text == query else text.contains(query)
    }
}
