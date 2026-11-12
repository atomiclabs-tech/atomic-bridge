package tech.atomiclabs.link

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPolicyTest {
    private val source = File("build.gradle.kts").readText()

    @Test fun releaseUsesProjectSigningAndTheAppKeepRules() {
        val release = releaseBlock(source)
        assertTrue(Regex("""signingConfig\s*=\s*signingConfigs\.getByName\("project"\)""").containsMatchIn(release))
        assertTrue(release.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
        assertTrue(release.contains("\"proguard-rules.pro\""))
    }

    @Test fun packageAndVersionNameStayTheSameForEveryBuildType() {
        assertTrue(Regex("""applicationId\s*=\s*"tech\.atomiclabs\.link"""").containsMatchIn(source))
        assertTrue(Regex("""versionName\s*=\s*"1\.0\.0"""").containsMatchIn(source))
        assertFalse(Regex("""\b(applicationIdSuffix|versionNameSuffix)\b""").containsMatchIn(source))
    }

    @Test fun noBuildTypeEnablesDebuggingExplicitly() {
        assertNonDebuggable(source)
    }

    @Test fun versionCodeReadsTheDocumentedPropertyWithDefaultEight() {
        assertTrue(Regex("""providers\.gradleProperty\("atomic\.versionCode"\)\s*\.orElse\("8"\)""").containsMatchIn(source))
    }

    @Test fun fullAndLauncherLabelsHaveSeparateBuildTimeDefaults() {
        assertTrue(source.contains("providers.gradleProperty(\"atomic.appLabel\").orElse(\"Atomic Bridge\")"))
        assertTrue(source.contains("providers.gradleProperty(\"atomic.launcherLabel\").orElse(\"Bridge\")"))
        assertTrue(source.contains("resValue(\"string\", \"app_name\", appLabel.get())"))
        assertTrue(source.contains("resValue(\"string\", \"launcher_name\", launcherLabel.get())"))
    }

    @Test fun debuggableReleaseFixtureFailsTheSamePolicy() {
        assertThrows(AssertionError::class.java) {
            assertNonDebuggable("buildTypes { release { isDebuggable = true } }")
        }
    }

    private fun assertNonDebuggable(text: String) {
        assertFalse("Build configuration must not enable debugging", Regex("""\bisDebuggable\s*=\s*true\b""").containsMatchIn(text))
    }

    private fun releaseBlock(text: String): String {
        val start = Regex("""\brelease\s*\{""").find(text)
        assertNotNull("An explicit release build type is required", start)
        val open = text.indexOf('{', start!!.range.first)
        var depth = 1
        for (index in open + 1 until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("Release build type is not closed")
    }
}
