package tech.atomiclabs.link.device.power

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Intent
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class TrampolineJobs(
    private val keyguard: KeyguardManager,
    private val clipboard: ClipboardManager,
    private val scope: CoroutineScope,
    private val complete: (Long, PortResult<String?>) -> Boolean,
    private val logger: Logger,
) {
    private data class Running(val id: Long, val job: String, var timeout: Job? = null, var started: Boolean = false)
    private val running = mutableMapOf<Activity, Running>()

    fun onCreate(activity: Activity, intent: Intent) {
        var id: Long? = null
        try {
            check(intent.hasExtra(TrampolineLauncher.EXTRA_ID)) { "Trampoline request id is missing." }
            id = intent.getLongExtra(TrampolineLauncher.EXTRA_ID, 0)
            check(id > 0) { "Trampoline request id is invalid." }
            val job = intent.getStringExtra(TrampolineLauncher.EXTRA_JOB)
            check(job == TrampolineLauncher.DISMISS || job == TrampolineLauncher.CLIPBOARD) { "Trampoline job is invalid." }
            val state = Running(id, job)
            running[activity] = state
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            state.timeout = scope.launch {
                delay(3_000)
                finish(activity, failure("timeout", IllegalStateException("Trampoline job timed out."), FailureKind.TIMED_OUT))
            }
        } catch (cause: Exception) {
            val result = failure("onCreate", cause, FailureKind.PLATFORM)
            if (running.containsKey(activity)) finish(activity, result) else {
                activity.finish()
                if (id != null && !complete(id, result)) lateCallback()
            }
        }
    }

    fun onWindowFocusChanged(activity: Activity, focused: Boolean) {
        val state = running[activity] ?: return
        if (!focused || state.started) return
        state.started = true
        if (state.job == TrampolineLauncher.DISMISS) {
            try { dismiss(activity) } catch (cause: Exception) {
                finish(activity, failure("dismiss", cause, FailureKind.PLATFORM))
            }
            return
        }
        val result = try {
            PortResult.Success(clipboardText())
        } catch (cause: Exception) {
            failure("readClipboard", cause, FailureKind.UNAVAILABLE)
        }
        finish(activity, result)
    }

    private fun clipboardText(): String {
        val present = clipboard.hasPrimaryClip()
        val clip = clipboard.primaryClip
        check(present == (clip != null)) { "The clipboard presence is inconsistent." }
        if (clip == null) return ""
        check(clip.itemCount > 0) { "The clipboard has no items." }
        return checkNotNull(clip.getItemAt(0).text) { "The clipboard has no text." }.toString()
    }

    fun onDestroyed(activity: Activity) {
        val state = running.remove(activity) ?: return
        state.timeout?.cancel()
        val result = failure("onDestroyed", IllegalStateException("Trampoline activity was destroyed."), FailureKind.UNAVAILABLE)
        if (!complete(state.id, result)) lateCallback()
    }

    private fun dismiss(activity: Activity) {
        if (!keyguard.isKeyguardLocked) {
            finish(activity, PortResult.Success(null))
            return
        }
        if (keyguard.isKeyguardSecure) {
            finish(activity, failure("secureKeyguard", IllegalStateException("The keyguard is secure."), FailureKind.REJECTED))
            return
        }
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish(activity, PortResult.Success(null))
            override fun onDismissCancelled() = finish(activity,
                failure("dismissCancelled", IllegalStateException("Keyguard dismissal was cancelled."), FailureKind.REJECTED))
            override fun onDismissError() = finish(activity,
                failure("dismissError", IllegalStateException("Keyguard dismissal failed."), FailureKind.REJECTED))
        })
    }

    private fun finish(activity: Activity, result: PortResult<String?>) {
        val state = running.remove(activity) ?: return
        state.timeout?.cancel()
        val outcome = try {
            activity.finish()
            result
        } catch (cause: Exception) {
            failure("finish", cause, FailureKind.PLATFORM)
        }
        if (!complete(state.id, outcome)) lateCallback()
    }

    private fun lateCallback() = logger.warn("TrampolineJobs", "complete", "state=caller_already_finished")

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("TrampolineJobs", operation, "state=job_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
