# Operator guide

Use your own Mac, Android phone and network. The agent or local caller runs on the same Mac as the relay. Keep a dedicated phone charged with screen lock set to None or Swipe.

Atomic Bridge 1.0.0: app 1.0.0 (code 8), relay 2.3.0, plugin 2.3.0.

## Build or obtain the APK

Download the APK from the [project releases](https://github.com/atomiclabs-tech/atomic-bridge/releases), or build it from the matching source checkout. Building needs JDK 21, Android SDK platform 37 and the pinned Gradle artifacts in your cache:

```sh
tools/build.sh :app:assembleRelease
tools/check-release-apk.sh app/build/outputs/apk/release/app-release.apk
```

The APK is `app/build/outputs/apk/release/app-release.apk`. The helper uses Gradle offline with no persistent daemon. Its header documents `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, `ATOMIC_SIGNING_DIR` and temporary-file paths. An offline cache error needs the missing artifact cached before retrying.

For your own builds, keep the entire private `.signing/` directory, including its keystore and properties file, in a restricted backup outside git. Reuse that signing identity for every update. A different key cannot update an installed app; losing the key can require uninstalling and pairing again. Downloaded releases and your own builds may use different keys, so choose one update path from the start.

## Install the Mac side

Use macOS 14 or newer with Python 3.12 and `uv`. Prepare the Python/package cache for the versions in `mac/pyproject.toml`; the setup and installation helpers use offline mode.

From the repository root:

```sh
tools/mac-venv.sh
tools/install-mac.sh --dry-run
tools/install-mac.sh
```

The installer puts the relay in `~/services/atomic-bridge`, writes its LaunchAgent and copies the plugin to `~/.hermes/plugins/atomic-bridge`. It creates a private admin credential and preserves an existing configuration and credential. The installer is safe to rerun; the pairing command starts the relay in the next step.

Enable the `atomic-bridge` plugin in your Hermes configuration. Use the host's normal plugin-loading procedure for an already-running gateway. Keep the agent/plugin on this Mac: control routes require both loopback access and the local admin credential. Other local callers can use the bundled helper. See the [Mac guide](../mac/README.md) for authentication and the configuration reference.

## Start the relay and pair

Run this from the repository root and leave its terminal open while you set up Atomic Bridge:

```sh
tools/pair.sh
```

The command offers to start the relay if needed. It uses a configured `ATOMIC_RELAY_PHONE_URL`, an existing non-loopback listener, or a guided choice among this Mac's private addresses. When a new listener is needed, it asks before changing `ATOMIC_RELAY_HOST` and restarting the relay. Loopback must remain in that explicit address list; wildcard listeners are refused.

**macOS may ask whether to accept incoming connections for the relay's Python. Choose Allow** for the Python process used by the relay on your intended private network. If you denied it, review System Settings → Network → Firewall and allow that Python, then retry pairing.

Tailscale is optional and recommended. With Tailscale on both the Mac and phone, choose the Mac's tailnet address: the phone reaches the relay from home Wi-Fi, other Wi-Fi or cellular, and Tailscale encrypts the link.

Without Tailscale, choose the Mac's home-network address only while both devices are on the same local network. That link is unencrypted, so anyone on that network could read it; guided pairing warns about this. The connection stops working when the phone leaves that network. The app refuses unencrypted addresses outside private networks; the relay requires explicit listener addresses including loopback and refuses to listen on all networks at once.

Other remote setups through your own HTTPS reverse proxy or tunnel are possible because the app accepts any `wss://` address. They expose the relay to the internet and are not guided or supported in v1.

The terminal prints the phone-facing address, a six-character one-time code, a pairing link and a QR. Keep all of that output private; do not paste it into chat, logs or shell history. The code is single use and expires in ten minutes. The command waits for that registration to be consumed and a phone to connect, then prints **Paired** or the failure. A reconnect from an already trusted phone does not complete a new pairing.

Continue with the phone setup below. If the code expires during setup, run `tools/pair.sh` again. `--no-qr` suppresses the QR; `--no-wait` returns after registration. A new session lasts 30 days by default; `tools/pair.sh --ttl never` deliberately opts into a non-expiring session. Existing stored sessions keep their original expiry and grants.

<a id="install-atomic-and-grant-permissions"></a>

## Install Atomic Bridge and grant permissions

Transfer the APK to the phone, open it in Files and allow installation from that source when Android asks. Install **Atomic Bridge**; its home-screen launcher is **Bridge**, and the app header and Android Settings use **Atomic Bridge**. For a normal update, tap **Update** over the existing app rather than uninstalling it.

Use Atomic Bridge's permission rows and App info shortcut. Android wording varies:

| Setting | What to grant or check |
| --- | --- |
| Restricted settings | In App info, choose **Allow restricted settings** if offered before enabling accessibility. If its status is unknown, check App info manually. |
| Accessibility | Enable Atomic Bridge in Android Accessibility settings and confirm its service is connected. |
| Battery and unused apps | Allow unrestricted battery use; turn off **Pause app activity if unused**. |
| Notification display | Optional: allow the silent foreground notification if you want it visible. |
| Notification access | Enable Atomic Bridge in Notification access to read posted and removed events. |
| SMS, Phone, Contacts | Grant each permission for the corresponding phone tools. |
| Location | Grant precise location and **Allow all the time** for background location. |
| All files access | Grant the app's special all-files access for shared-file tools. |
| Microphone and Phone state | Needed by the diagnostic call-audio probe; they do not provide live call audio. |

For accessibility recovery, authorize USB debugging temporarily and make this one-time grant on your own phone:

```sh
adb shell pm grant tech.atomiclabs.link android.permission.WRITE_SECURE_SETTINGS
```

USB debugging, Developer Options and a cable are not needed at runtime. Without this grant, re-enable accessibility manually after Android turns it off. Atomic Bridge uses accessibility screenshots; secure windows and screenshot rate limits can return errors.

Enter the address and code displayed by `tools/pair.sh` into Atomic Bridge and tap **Pair**, or scan the terminal QR/open the pairing link on the phone. A link shows the full relay address before pairing. Review it and tap **Pair** to confirm; **Cancel** is the default and changes nothing. Pairing replaces a stored session only after authentication succeeds. Confirm **Connected** on the phone and **Paired** in the Mac terminal.

## Check your setup

From the repository root on the relay Mac:

```sh
tools/py.sh ../tools/call_tool.py android_ping '{}'
```

This loads the checkout plugin and calls the paired phone with the local admin credential. Check for a successful response; do not publish raw phone results. A 401 means the caller needs the correct admin credential configuration, not the phone's session token. For the optional private vault helper, follow [Bitwarden setup](BITWARDEN.md) and run its `--check` before typing a secret.

## First commands

List apps, read the accessible screen and inspect the foreground package:

```sh
tools/py.sh ../tools/call_tool.py android_get_apps '{}'
tools/py.sh ../tools/call_tool.py android_read_screen '{}'
tools/py.sh ../tools/call_tool.py android_current_app '{}'
```

Use the [tools reference](TOOLS.md) to choose the next action. Focus the intended field before typing. Screenshots can be saved with `tools/call_tool.py`'s `--image-dir` option pointing to a private existing directory. Read [Bitwarden setup](BITWARDEN.md) to type a vault field without sending its value through chat or tool output.

## Update

1. Keep your private signing backup and a usable rollback APK before updating. For the code-8 release, build the code-9 rollback from your prior release commit with `tools/build-rollback-apk.sh <PRIOR_RELEASE_COMMIT> 9`, using the same signing identity. For later updates, choose a rollback code higher than the update you might need to undo.
2. Obtain or build the new release APK. Install it over Atomic Bridge with the same key; do not uninstall or clear app data. A normal update preserves pairing.
3. From the new source checkout, review `tools/py.sh ../tools/upgrade.py --dry-run`, then run `tools/py.sh ../tools/upgrade.py`. The script backs up the installed relay/plugin/configuration, updates the files and checks the relay. Keep its backup directory and printed rollback command private.
4. Activate changed plugin files using your host's normal plugin-loading procedure. The upgrade script manages the relay's own service, and gateway lifecycle remains under the host's controls. Check `android_ping` again after the update.

## Rollback

For the Mac side, use the exact command printed by the failed upgrade, or run:

```sh
tools/py.sh ../tools/upgrade.py --rollback --backup "<BACKUP_DIRECTORY>"
```

Replace the placeholder with the upgrade's usable backup directory. Rollback restores that snapshot and checks it with the restored admin credential. Re-running an interrupted upgrade reuses its started backup; stop at a failed step and use its printed recovery command.

Android refuses a lower version code. To roll the app back without losing its session, install the prepared higher-code rollback APK with the same signing key over the app. For this release, check the prepared rollback with `tools/check-release-apk.sh build/rollback/Atomic-rollback-<short-commit>-9.apk --version-code 9 --debuggable`. Re-pair only if you deliberately removed app data or need a new session.

## Uninstall

Disable the Atomic Bridge plugin in the host's configuration, then unload the relay's LaunchAgent on your Mac:

```sh
launchctl bootout "gui/$(id -u)/com.atomic.bridge-relay"
```

After it is unloaded, remove `~/Library/LaunchAgents/com.atomic.bridge-relay.plist`, `~/services/atomic-bridge` and `~/.hermes/plugins/atomic-bridge` if you no longer need them. Keep private backups you intend to retain. The shared sessions file and unrelated plugins may be used by other software; remove those only as part of your own account cleanup. Uninstall Atomic Bridge through Android App info to remove the app and its locally stored pairing.

## Logs and limits

The relay log is `~/services/atomic-bridge/logs/relay.log`; `ATOMIC_RELAY_LOG_LEVEL=DEBUG` enables extra diagnostics. Phone logs use the `AtomicLink` tag. Keep codes, links, credentials, screen/contact/message content, filenames, files, audio and images out of logs and support reports. See the [Mac guide](../mac/README.md) for log rotation and notification/media boundaries.

- Use a dedicated phone with None or Swipe screen lock. Reads can work with the display off; screenshots wake it. **Kill link** keeps connection and control off across restarts until **Turn link back on** is tapped.
- There are no per-command prompts, app blocklists or timed leases. The pairing confirmation protects pairing only. Accessibility can inspect sensitive views wherever Android exposes them.
- `android_open_app` reports `foreground_verified:true` only when its package was seen in front. A task covered by another app can return unverified success with a hint; inspect the screen before acting. An unconfirmed launch can return `timeout` after five seconds.
- Searches cover only the accessibility tree and stop at 20,000 nodes or 20 seconds. Check `truncated` and `visited`; some pages expose no searchable text.
- An unreachable socket-open attempt fails in about ten seconds and keeps its error visible while retrying. Saving the same address does nothing; changing it preserves the stored session. Cancelled pairing links leave the address and session unchanged.
- Notification content reaches the relay before its allow/deny filters control buffering/publication. Removal events can appear in history; text filters and notification waits use posts.
- File transfers are limited to 256 MiB and verified by size and SHA-256. Relay media-root refusals do not disclose configured paths.
- Sharing and MMS stop at Android's compose screen. Calls can be placed, but there is no live call-audio capture or injection; the audio tool is diagnostic only. The return-to-phone tool currently reports `unsupported_command`.

See [LICENSE](../LICENSE) and [NOTICE](../NOTICE) for the MIT license and retained Hermes-Relay attribution, including Copyright (c) 2026 Axiom Labs.
