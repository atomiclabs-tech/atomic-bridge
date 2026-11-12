package tech.atomiclabs.link.core.command

import org.junit.Assert.*
import org.junit.Test

class FailureDiagnosticsTest {
    @Test fun removesMessagesAtEveryLevelButKeepsTypesAndFrames() {
        val inner = IllegalArgumentException("private-nested-fixture")
        val original = IllegalStateException("private-top-fixture", inner)
        original.addSuppressed(UnsupportedOperationException("private-suppressed-fixture"))
        val safe = FailureDiagnostics.sanitized(original)
        val diagnostic = safe.stackTraceToString()
        assertFalse(diagnostic.contains("private-"))
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("IllegalArgumentException"))
        assertTrue(diagnostic.contains("UnsupportedOperationException"))
        assertArrayEquals(original.stackTrace, safe.stackTrace)
        assertArrayEquals(inner.stackTrace, safe.cause!!.stackTrace)
    }

    @Test fun cyclicCausesAreBounded() {
        val first = Exception("private-first")
        val second = Exception("private-second", first)
        first.initCause(second)
        assertFalse(FailureDiagnostics.sanitized(first).stackTraceToString().contains("private-"))
    }

    @Test fun sharedSuppressedGraphsCannotMultiplyDiagnosticWork() {
        val levels = List(10) { Exception("private-level-$it") }
        for (index in 0 until levels.lastIndex) {
            levels[index].initCause(levels[index + 1])
            for (other in levels.drop(index + 1)) levels[index].addSuppressed(other)
        }
        fun count(cause: Throwable): Int = 1 + (cause.cause?.let(::count) ?: 0) + cause.suppressed.sumOf(::count)
        val safe = FailureDiagnostics.sanitized(levels.first())
        assertTrue(count(safe) <= 32)
        assertFalse(safe.stackTraceToString().contains("private-"))
    }

    @Test fun onlyValidatedRequestIdsAndRegisteredPathsEnterTheLogContext() {
        val id = "11111111-2222-3333-4444-555555555555"
        assertEquals("request_id=$id path=/example", FailureDiagnostics.requestContext(id, "/example"))
        val context = FailureDiagnostics.requestContext("private\nvalue", null)
        assertEquals("request_id=<invalid> path=<unregistered>", context)
    }
}
