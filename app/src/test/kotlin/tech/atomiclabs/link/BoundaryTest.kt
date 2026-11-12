package tech.atomiclabs.link

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryTest {
    private val root = File("..").canonicalFile
    // Public trees omit private Rules; the equality assertion below keeps this copy exact.
    private val rules = mapOf(
        "app/src/main/kotlin/tech/atomiclabs/link/core/" to listOf(
            "android",
            "androidx",
            "okhttp3",
            "tech.atomiclabs.link.device",
            "tech.atomiclabs.link.net",
            "tech.atomiclabs.link.storage",
            "tech.atomiclabs.link.service",
            "tech.atomiclabs.link.ui",
        ),
        "app/src/main/kotlin/tech/atomiclabs/link/device/" to listOf(
            "okhttp3",
            "tech.atomiclabs.link.net",
            "tech.atomiclabs.link.storage",
            "tech.atomiclabs.link.service",
            "tech.atomiclabs.link.ui",
        ),
        "app/src/main/kotlin/tech/atomiclabs/link/net/" to listOf(
            "tech.atomiclabs.link.device",
            "tech.atomiclabs.link.storage",
            "tech.atomiclabs.link.service",
            "tech.atomiclabs.link.ui",
        ),
        "app/src/main/kotlin/tech/atomiclabs/link/storage/" to listOf(
            "okhttp3",
            "tech.atomiclabs.link.device",
            "tech.atomiclabs.link.net",
            "tech.atomiclabs.link.service",
            "tech.atomiclabs.link.ui",
        ),
        "app/src/main/kotlin/tech/atomiclabs/link/ui/" to listOf(
            "okhttp3",
            "tech.atomiclabs.link.device",
            "tech.atomiclabs.link.net",
            "tech.atomiclabs.link.storage",
        ),
    )

    @Test fun mainSourcesRespectTheRulesDeclaredInAgents() {
        val privateRules = File(root, "AGENTS.md")
        if (privateRules.exists()) {
            assertTrue("Private Rules must be a regular file", privateRules.isFile)
            assertEquals("Public boundary copy must match private Rules exactly", readRules(privateRules.readText()), rules)
        }
        assertEquals("Every protected package must have a rule", 5, rules.size)
        val violations = File(root, "app/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { violations(it.relativeTo(root).invariantSeparatorsPath, it.readText()).asSequence() }
            .toList()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test fun fixtureWithAndroidImportFailsThePolicyAssertion() {
        val failure = assertThrows(AssertionError::class.java) {
            assertPolicy("core/Bad.kt", "package example\nimport android.app.Application\n")
        }
        assertTrue(failure.message!!.contains("android.app.Application"))
    }

    @Test fun forbiddenSiblingImportsAndAliasesAreRejected() {
        val found = violations(
            sourcePath("device/Bad.kt"),
            "import tech.atomiclabs.link.net.Socket as Renamed\nimport okhttp3.*\n",
        )
        assertEquals(2, found.size)
    }

    @Test fun allowedImportsAndSimilarPackageNamesPass() {
        assertPolicy("core/Good.kt", "import kotlinx.coroutines.Job\nimport androidish.Type\n")
        assertPolicy("device/Good.kt", "import android.app.Application\nimport tech.atomiclabs.link.core.log.Logger")
    }

    @Test fun changingTheDeclaredRuleChangesEnforcement() {
        val fixtureRules = readRules(
            "### Boundaries\n- `app/src/main/kotlin/tech/atomiclabs/link/core/` must not import `example.bad`\n",
        )
        assertEquals(listOf("example.bad"), fixtureRules.values.single())
    }

    private fun assertPolicy(relativePath: String, source: String) {
        val found = violations(sourcePath(relativePath), source)
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    private fun sourcePath(relativePath: String) = "app/src/main/kotlin/tech/atomiclabs/link/$relativePath"

    private fun violations(path: String, source: String): List<String> {
        val forbidden = rules.entries.singleOrNull { path.startsWith(it.key) }?.value ?: return emptyList()
        val imports = Regex("(?m)^\\s*import\\s+([\\w.]+(?:\\*)?)").findAll(source)
        return imports.map { it.groupValues[1] }.filter { imported ->
            forbidden.any { imported == it || imported.startsWith("$it.") }
        }.map { "$path imports forbidden $it" }.toList()
    }

    private fun readRules(text: String): Map<String, List<String>> {
        check(text.contains("### Boundaries")) { "AGENTS.md is missing its Boundaries section." }
        val section = text.substringAfter("### Boundaries").substringBefore("\n### ")
        return Regex("(?m)^- `([^`]+)` must not import (.+)$").findAll(section).associate { match ->
            val forbidden = Regex("`([^`]+)`").findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
            check(forbidden.isNotEmpty()) { "Boundary ${match.groupValues[1]} has no forbidden imports." }
            match.groupValues[1] to forbidden
        }
    }
}
