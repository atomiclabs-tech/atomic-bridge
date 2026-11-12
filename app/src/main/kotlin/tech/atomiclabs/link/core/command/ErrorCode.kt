// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.command

/** The relay/tool contract's complete command failure vocabulary. */
enum class ErrorCode(val wireValue: String) {
    UNKNOWN_COMMAND("unknown_command"), UNSUPPORTED_COMMAND("unsupported_command"), BAD_REQUEST("bad_request"),
    ACCESSIBILITY_DISABLED("accessibility_disabled"), KEYGUARD_BLOCKED("keyguard_blocked"), WAKE_FAILED("wake_failed"),
    NODE_NOT_FOUND("node_not_found"), NO_FOCUSED_INPUT("no_focused_input"), GESTURE_FAILED("gesture_failed"),
    UNSUPPORTED_KEY("unsupported_key"), WAIT_TIMEOUT("wait_timeout"),
    SCREENSHOT_RATE_LIMITED("screenshot_rate_limited"), SECURE_WINDOW("secure_window"),
    SCREENSHOT_FAILED("screenshot_failed"), UPLOAD_FAILED("upload_failed"), MEDIA_FETCH_FAILED("media_fetch_failed"),
    APP_NOT_FOUND("app_not_found"), NO_HANDLER("no_handler"), CLIPBOARD_UNAVAILABLE("clipboard_unavailable"),
    PERMISSION_DENIED("permission_denied"), LOCATION_UNAVAILABLE("location_unavailable"),
    SMS_FAILED("sms_failed"), SMS_UNCONFIRMED("sms_unconfirmed"), TIMEOUT("timeout"), INTERNAL_ERROR("internal_error"),
    FILE_NOT_FOUND("file_not_found"), PATH_FORBIDDEN("path_forbidden"), FILE_TOO_LARGE("file_too_large"),
    FILE_EXISTS("file_exists"), CHECKSUM_MISMATCH("checksum_mismatch"), STORAGE_UNAVAILABLE("storage_unavailable"),
    AUDIO_UNAVAILABLE("audio_unavailable"),
}
