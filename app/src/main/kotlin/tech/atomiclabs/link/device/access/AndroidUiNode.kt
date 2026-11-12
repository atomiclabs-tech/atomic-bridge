package tech.atomiclabs.link.device.access

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidUiNode(
    private val node: AccessibilityNodeInfo,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : UiNode {
    override suspend fun read(): PortResult<UiNodeProperties> = checked("read") {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        UiNodeProperties(
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom), windowId = node.windowId,
            packageName = node.packageName?.toString(), text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(), className = node.className?.toString(),
            viewId = node.viewIdResourceName, hintText = node.hintText?.toString(), childCount = node.childCount,
            clickable = node.isClickable, longClickable = node.isLongClickable, scrollable = node.isScrollable,
            editable = node.isEditable, focusable = node.isFocusable, focused = node.isFocused,
            selected = node.isSelected, enabled = node.isEnabled, checkable = node.isCheckable,
            checked = if (node.isCheckable) node.isChecked else null, password = node.isPassword,
            selectionStart = node.textSelectionStart, selectionEnd = node.textSelectionEnd,
            showingHintText = node.isShowingHintText,
            supportedActions = node.actionList.mapNotNull { actionKinds[it.id] }.toSet(),
        )
    }

    override suspend fun children(): PortResult<List<UiNode>> = checked("children") {
        (0 until node.childCount).mapNotNull { index -> node.getChild(index)?.let(::wrap) }
    }

    override suspend fun parent(): ReadValue<UiNode> = optional("parent") { node.parent }

    override suspend fun nextInputFocus(): ReadValue<UiNode> = optional("nextInputFocus") {
        node.focusSearch(View.FOCUS_FORWARD)
    }

    override suspend fun perform(action: NodeAction): PortResult<Unit> {
        val result = checked("perform") {
            when (action) {
                is NodeAction.Simple -> node.performAction(actionKinds.entries.single { it.value == action.kind }.key)
                is NodeAction.SetText -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                })
                is NodeAction.SetSelection -> node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, action.start)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, action.end)
                })
            }
        }
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Success -> if (result.value) PortResult.Success(Unit) else
                failure("perform", IllegalStateException("The node action was refused."), FailureKind.REJECTED)
        }
    }

    private fun wrap(value: AccessibilityNodeInfo): UiNode = AndroidUiNode(value, logger, dispatcher)

    private suspend fun optional(operation: String, read: () -> AccessibilityNodeInfo?): ReadValue<UiNode> =
        when (val result = checked(operation, read)) {
            is PortResult.Failure -> ReadValue.Unreadable(result.failure)
            is PortResult.Success -> result.value?.let { ReadValue.Present(wrap(it)) } ?: ReadValue.Absent
        }

    private suspend fun <T> checked(operation: String, read: () -> T): PortResult<T> = try {
        PortResult.Success(withTimeout(5_000) { withContext(dispatcher) { read() } })
    } catch (cause: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure(operation, cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure(operation, cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidUiNode", operation, "state=read_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }

    private companion object {
        val actionKinds = mapOf(
            AccessibilityNodeInfo.ACTION_CLICK to NodeActionKind.CLICK,
            AccessibilityNodeInfo.ACTION_LONG_CLICK to NodeActionKind.LONG_CLICK,
            AccessibilityNodeInfo.ACTION_FOCUS to NodeActionKind.FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS to NodeActionKind.CLEAR_FOCUS,
            AccessibilityNodeInfo.ACTION_SET_TEXT to NodeActionKind.SET_TEXT,
            AccessibilityNodeInfo.ACTION_SET_SELECTION to NodeActionKind.SET_SELECTION,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to NodeActionKind.SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to NodeActionKind.SCROLL_BACKWARD,
            android.R.id.accessibilityActionScrollUp to NodeActionKind.SCROLL_UP,
            android.R.id.accessibilityActionScrollDown to NodeActionKind.SCROLL_DOWN,
            android.R.id.accessibilityActionScrollLeft to NodeActionKind.SCROLL_LEFT,
            android.R.id.accessibilityActionScrollRight to NodeActionKind.SCROLL_RIGHT,
            android.R.id.accessibilityActionImeEnter to NodeActionKind.IME_ENTER,
        )
    }
}
