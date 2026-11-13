# Tools

Atomic Bridge registers 42 tools. Each line below uses the tool schema description; arguments are defined in the plugin schemas. Start with the [operator guide](OPERATOR.md) and keep tool results private.

## Screen

- `android_read_screen` — Read the accessibility tree on Atomic Bridge, with optional node bounds.
- `android_find_nodes` — Find matching accessibility nodes on Atomic Bridge using combined optional filters.
- `android_describe_node` — Read the full property bag for one accessibility node on Atomic Bridge.
- `android_screenshot` — Capture the Atomic Bridge screen and attach a bounded PNG or JPEG image with a text summary.
- `android_wait` — Wait for a matching accessibility node to appear on Atomic Bridge.
- `android_screen_hash` — Get a SHA-256 fingerprint of the current accessibility tree on Atomic Bridge.
- `android_diff_screen` — Compare the current Atomic Bridge screen with an earlier accessibility-tree hash.
- `android_events` — Read recent accessibility events captured by the phone app.
- `android_event_stream` — Start or stop accessibility-event capture on Atomic Bridge. Stopping clears its event buffer.

## Input

- `android_tap` — Tap a node on Atomic Bridge by node_id, or tap screen coordinates x and y.
- `android_tap_text` — Tap the first visible text match on Atomic Bridge, using exact or substring matching.
- `android_long_press` — Long press a node or screen coordinates on Atomic Bridge. Duration must be 100 to 3000 ms.
- `android_type` — Type into the focused field on Atomic Bridge, optionally clearing the existing text.
- `android_swipe` — Swipe up, down, left or right on Atomic Bridge with the selected distance.
- `android_drag` — Drag between screen coordinates on Atomic Bridge. Duration is clamped to 100 to 3000 ms. Optionally hold at the start point before moving.
- `android_scroll` — Scroll the Atomic Bridge screen or a selected scrollable node.
- `android_press_key` — Press a supported hardware or software key on Atomic Bridge.
- `android_unlock` — Unlock the Atomic Bridge phone's PIN lock screen using the PIN file configured on the relay Mac; the PIN is never passed as an argument. One attempt per call.
- `android_clipboard_read` — Read the current plain-text clipboard on Atomic Bridge.
- `android_clipboard_write` — Write plain text to the clipboard on Atomic Bridge.

## Apps and navigation

- `android_open_app` — Launch an installed package on Atomic Bridge.
- `android_get_apps` — List installed packages and display labels on Atomic Bridge.
- `android_current_app` — Get the foreground package and activity on Atomic Bridge.
- `android_send_intent` — Send an Android activity intent from Atomic Bridge with the supplied fields.
- `android_broadcast` — Send an Android broadcast from Atomic Bridge with the supplied fields.
- `android_return_to_hermes` — Request return to the phone app. Atomic Bridge currently reports unsupported_command for this command.

## Notifications

- `android_notifications_recent` — List recent posted and removed notification events from Atomic Bridge, newest first.
- `android_wait_notification` — Wait for the first matching notification from Atomic Bridge, or report no match. Removal events never match.

## Files and media

- `android_list_files` — List an accessible folder on Atomic Bridge, including file sizes and modification times.
- `android_pull_file` — Pull a file up to 256 MiB from Atomic Bridge, verify its size and SHA-256, and save it atomically.
- `android_push_file` — Push a local file up to 256 MiB to Atomic Bridge with size and SHA-256 verification.
- `android_share_media` — Share text or attachments through Atomic Bridge. Host files are registered before the phone app fetches them.
- `android_send_mms` — Prepare an MMS compose handoff on Atomic Bridge with text or attachments. This does not confirm carrier delivery.
- `android_media` — Control system media playback on Atomic Bridge: play, pause, toggle, next or previous.

## Phone functions

- `android_location` — Get the last known phone location from Atomic Bridge; missing permission is reported as an error.
- `android_search_contacts` — Search contacts on Atomic Bridge by name and return matching phone numbers.
- `android_call` — Place a phone call from Atomic Bridge to the supplied number.
- `android_send_sms` — Send an SMS from Atomic Bridge and report the phone app result.
- `android_call_audio_probe` — Measure call-audio capture and playback on Atomic Bridge and return the phone app report.

## Setup and diagnostics

- `android_ping` — Ask the phone app to report whether it is responding.
- `android_setup` — Diagnose Atomic Bridge using loopback health, status and ping requests. Optionally register a pairing code without expiry; supplied session tokens are ignored. No settings are written.
- `android_macro` — Run an ordered workflow through the Atomic Bridge tools, stop at the first error, and retain screenshot summaries only.
