package tech.atomiclabs.link.device.audio

import java.io.IOException
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger

internal class AudioCleanup(private val logger: Logger, private val component: String) {
    fun finish(primary: Throwable?, operation: String, action: () -> Unit): Throwable? = try {
        action()
        primary
    } catch (cause: IOException) { failed(primary, operation, cause)
    } catch (cause: SecurityException) { failed(primary, operation, cause)
    } catch (cause: IllegalArgumentException) { failed(primary, operation, cause)
    } catch (cause: IllegalStateException) { failed(primary, operation, cause)
    } catch (cause: UnsupportedOperationException) { failed(primary, operation, cause) }

    private fun failed(primary: Throwable?, operation: String, cause: Exception): Throwable {
        logger.error(component, operation, "state=cleanup_failed", FailureDiagnostics.sanitized(cause))
        return if (primary == null) cause else primary.also { if (it !== cause) it.addSuppressed(cause) }
    }
}
