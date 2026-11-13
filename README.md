<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/atomic-bridge-lockup-dark.svg">
    <img alt="Atomic Bridge" src="docs/brand/atomic-bridge-lockup-light.svg" width="520">
  </picture>
</p>

<h3 align="center">Give your AI agent a real Android phone.</h3>

<p align="center">
  It reads the screen, taps, types, swipes, catches notifications and moves files.<br>
  No root. No cloud service in the middle. No USB cable at runtime.
</p>

<p align="center">
  Android 14+ app &nbsp;·&nbsp; Mac relay &nbsp;·&nbsp; Hermes plugin with 42 tools &nbsp;·&nbsp; MIT
</p>

---

## Your agent can do everything except pick up the phone

It writes code, clears your inbox and runs your calendar. Then it hits an app that only lives on a phone: the parking app, the delivery app, the router app, the text with the login code. No API, no website, no way in.

So the job comes back to you. You read codes off the lock screen. You copy the photo over by hand. You tap through the same five screens every morning so your agent can carry on.

Between you and me, that's backwards. You hired the agent so you could stop being the hands.

## Atomic Bridge is the missing hand

Put a spare Android phone on a charger. Install the **Atomic Bridge** app and pair it with your Mac. Your agent can now use that phone the way you do: look at the screen, decide, tap.

| Your agent can | Which means |
|---|---|
| **Read accessible screen content as text** (the accessibility tree, not a guess from pixels) | It works inside apps that have no API where Android exposes their views |
| **Tap, type, swipe, scroll, long-press, drag** and press keys | It can finish the flow, not just describe it to you |
| **Take screenshots** | When text isn't enough, it sees what you'd see |
| **Read and wait for notifications** | It catches the login code or the "order arrived" alert the moment it lands |
| **Move files both ways**, up to 256 MiB, checked by size and SHA-256 | Photos off the phone, documents onto it, nothing corrupted on the way |
| **Open apps, send intents, use the clipboard and media keys** | It moves around the phone without fumbling through menus |
| **Send texts, place calls, look up contacts, get location** | The phone's own abilities become agent abilities |

## How it works

```text
 Your agent ──► atomic-bridge plugin ──► relay on your Mac ──► Atomic Bridge app on the phone
 (Hermes, or     (42 android_* tools)     (Python 3.12,           (accessibility service,
  anything that                            aiohttp, port 8767)     foreground service)
  speaks HTTP)
```

Three parts, and you run all of them. **The agent, caller and plugin run on the same Mac as the relay**: control routes accept loopback requests only. The phone keeps a WebSocket open over Tailscale or the same local network as your Mac. Screen and notification content reaches your Mac; no hosted relay is required.

## Built to stay connected

A phone in a drawer is only useful if it stays reachable without you.

- **Recovers by itself** after a crash, reboot, Settings force-stop or Wi-Fi drop. With the one-time `WRITE_SECURE_SETTINGS` grant, accessibility also restores itself. Nobody has to open the app.
- **Keeps its pairing through updates.** New builds install over the old one with the same signing key.
- **Backs off politely.** If the login stops working, the app says "re-pair needed" and retries three times, five minutes apart, instead of hammering the relay.
- **No hacks on the phone.** No root, no unlocked bootloader, no Developer Options at runtime. Screenshots go through accessibility, so there's no "start recording?" prompt every time.

## Control behavior and limits

Tree commands read the current accessibility tree. `android_open_app` waits up to five seconds for the launched package or a window event from its task. `foreground_verified:true` means the package itself was observed in front. If another app's Activity covers the launched task, success can carry `foreground_verified:false` and a `hint`; callers should inspect the current screen before acting. With accessibility off, a successful launch is also unverified. A launch that cannot be confirmed within the wait returns `timeout`.

Text search uses Unicode normalisation, collapsed whitespace and case folding, matching text and content descriptions rather than hints. Searches stop at 20,000 visited nodes or 20 seconds and report `truncated` and `visited`. A missing result is conclusive only for the accessible content actually searched. Text taps use a clickable ancestor when needed; dragging supports an optional hold before movement.

Notification history uses each event's own post or removal time; text filters and notification waits match posted content only. Saving a different relay address reconnects using the stored session, and saving the same address does nothing. An unreachable socket-open attempt is bounded to about ten seconds; its error stays visible while retries continue. Pairing is replaced only after a new pairing succeeds. File pushes outside the configured media roots return a fixed refusal without listing those roots.

The [operator guide](docs/OPERATOR.md) explains setup and operating limits. The [tools reference](docs/TOOLS.md) lists the complete inventory.

## Works with the agent you already have

- **Hermes:** a native plugin. Enable it and your agent gets 42 `android_*` tools.
- **Claude Code, Codex, your own scripts on that Mac:** use the local HTTP API with the admin credential, or the bundled helper. A phone session token cannot authorize control routes.

```sh
tools/py.sh ../tools/call_tool.py android_ping '{}'
```

Atomic Bridge 1.0.1: app 1.0.1 (code 10), relay 2.4.0, plugin 2.4.0.

## How to

1. **Install the Mac side:** prepare Python and run the installer in the [Mac installation steps](docs/OPERATOR.md#install-the-mac-side).
2. **Install Atomic Bridge:** obtain the APK and grant its one-time permissions using the [phone setup steps](docs/OPERATOR.md#install-atomic-and-grant-permissions).
3. **Pair:** run `tools/pair.sh` on the Mac, then enter the displayed address and code in Atomic Bridge or scan the terminal QR. Follow the [pairing guide](docs/OPERATOR.md#start-the-relay-and-pair).
4. **Try the first commands:** [check the connection](docs/OPERATOR.md#check-your-setup), then [read a screen and list apps](docs/OPERATOR.md#first-commands).
5. **Type a vault secret privately:** use the optional [Bitwarden helper](docs/BITWARDEN.md).
6. **Update:** follow the [update steps](docs/OPERATOR.md#update) to keep your signing identity and pairing.
7. **Uninstall:** follow the [removal steps](docs/OPERATOR.md#uninstall) for the app, relay and plugin.

Tailscale is optional and recommended for an encrypted connection from any network. Without it, keep both devices on the same local network; that link is unencrypted, and guided pairing warns before using it. See the [network choices](docs/OPERATOR.md#start-the-relay-and-pair).

## Dependencies and requirements

- **Mac:** macOS 14 or newer, Python 3.12 and `uv`. The offline setup helpers need Python and the pinned packages in the local cache; prepare that cache before running them. The relay uses aiohttp; the plugin uses the Python standard library.
- **Building the APK:** JDK 21, Android SDK platform 37 and a prepared Gradle cache. The wrapper and [build instructions](docs/OPERATOR.md#build-or-obtain-the-apk) select the pinned toolchain.
- **Phone:** Android 14 or newer, kept charged and dedicated to this use.
- **Screen lock:** None, Swipe, or a PIN with the relay-side unlock. See [unlock setup](docs/OPERATOR.md#unlock-with-a-pin).
- **Agent:** Hermes Agent on the relay Mac for the plugin; another local caller can use the HTTP API or helper.
- **Network:** Tailscale is optional and recommended. With Tailscale on the Mac and phone, the phone reaches the relay from home Wi-Fi, other Wi-Fi or cellular; Tailscale encrypts the link.
- **Optional:** the Bitwarden CLI for private secret entry.

## Limitations

- Without Tailscale, the phone works only on the same local network as the Mac. A home-network address uses an unencrypted link that anyone on that network could read; guided pairing warns about this. The connection stops working when the phone leaves that network.
- Other remote setups through your own HTTPS reverse proxy or tunnel are possible because the app accepts any `wss://` address. They expose the relay to the internet and are not guided or supported in v1.
- Control depends on Android accessibility. Content omitted from the accessibility tree, including some web pages and custom views, cannot be found as text. Secure windows can refuse screenshots.
- No root or privileged system control, and no live call audio. The call-audio tool is a diagnostic probe.
- Remote unlock supports a PIN only, not a pattern, password, fingerprint or face. Nothing runs before the first unlock after a reboot, so `android_unlock` cannot help there. A wrong PIN spends a real lockout attempt. Unlock is tested on Pixel 8a running Android 16 (from doze, already unlocked, wrong PIN). The contributor reported unlock working on Samsung One UI 8 with a pre-release build; it has not been retested with 1.0.1.
- Sharing and MMS use Android's compose flow. A person completes Send.
- Only macOS is tested for the relay and plugin. The phone app supports Android; it does not control iPhones.

## Security

Atomic Bridge is powerful because it gets out of the way. That cuts both ways, so here is what you're signing up for.

- **Use a dedicated phone.** There are no per-command confirmation prompts and no app blocklist. Your agent can tap anything, including "Delete." Don't use the phone that holds your bank and your life.
- **The admin secret is full control.** A local process that can read it can control the paired phone. An untrusted tool or prompt that convinces your agent to act has the agent's reach. Protect the Mac account and credential; keep secrets and phone content out of chat, logs and git. Phone session tokens authorize only the phone WebSocket and media upload/download, not agent commands.
- **PIN unlock uses a private file on the Mac.** The relay reads it for each `android_unlock` call; the PIN never passes through the agent, chat, shell process arguments or tool output. Use unlock only over Tailscale or loopback: `ws://` sends the PIN in cleartext, and Tailscale encrypts that transport. Keep the file private and follow the [unlock setup](docs/OPERATOR.md#unlock-with-a-pin).
- With None or Swipe screen lock, reads work without waking display; screenshots wake it.
- **Always back on, with one kill switch.** Crashes, reboots and even a Settings force stop bring the link back by itself. The one-time `WRITE_SECURE_SETTINGS` grant lets accessibility restore itself too. Tap **Kill link** in the app and the link stays off, even after a reboot, until **Turn link back on** is tapped. Without the grant, Android may keep accessibility enabled, but the link stays off; the status screen explains when screen control remains on.
- **Full accessibility access.** Atomic Bridge declares `android:isAccessibilityTool="true"` and `android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"`. It can inspect sensitive views, including banking apps where Android exposes them, and perform gestures. Secure-window screenshot restrictions do not make the rest of the phone safe from an authorized caller.
- **Notifications reach the relay first.** With Notification access, the phone streams posted and removed events to the Mac. `ATOMIC_RELAY_NOTIFY_ALLOW` and `ATOMIC_RELAY_NOTIFY_DENY` filter relay buffering and publication, with deny taking precedence. They do not prevent notification contents travelling from the phone to the relay.
- **Keep control local and protect the phone connection.** The agent and plugin run on the relay Mac. Keep a loopback listener and add only the explicit address literals needed for the phone; wildcard binds are refused. Tailscale encrypts traffic between your devices; a home-network link without it is unencrypted. The app refuses unencrypted addresses outside private networks. The relay listens only on explicit addresses, requires loopback and refuses wildcard listeners, rather than listening on all networks at once. Never expose local administration or control. Keep `admin.secret` a private regular file with mode `0600` and at least 32 characters. See the [Mac guide](mac/README.md) for setup and credential rotation.
- **Sharing and MMS stop at the compose screen.** A person taps Send.
- **No live call audio.** Your agent can place a call, but it can't hear or speak on it.
- **Android only.** iPhones don't let one app control others.
- **Mac only, for now.** The relay is plain Python and should run on Linux, but only macOS is tested.

## Troubleshooting

- **Cannot reach the relay:** check that both devices can reach the selected private network, that the relay is running and that its Python is allowed through the [Mac firewall](docs/OPERATOR.md#start-the-relay-and-pair). With Tailscale, check it is connected on both devices; without it, the phone must remain on the Mac's local network.
- **Pairing rejected or expired:** run `tools/pair.sh` again and use the newly displayed code within ten minutes. Check the address before confirming the pairing link.
- **Accessibility turned off:** allow restricted settings in App info if Android asks, then enable Atomic Bridge in Accessibility. The optional one-time grant restores accessibility after interruptions.
- **Incoming-connections prompt:** allow the relay's Python process on the intended private network. See the [pairing instructions](docs/OPERATOR.md#start-the-relay-and-pair).
- **Tools return 401:** the caller needs the local admin credential, not the phone's session token. Check the [Mac authentication guide](mac/README.md) and restart the command after correcting its configuration.

## License and credits

MIT, see [LICENSE](LICENSE). Atomic Bridge started from Hermes-Relay by Axiom Labs (MIT). [NOTICE](NOTICE) reproduces **Copyright (c) 2026 Axiom Labs** and lists the reused files; ported sources identify their upstream path and commit. The project license retains **Copyright (c) 2026 Atomic Labs**.

<p align="center"><sub>Made by <a href="https://atomiclabs.tech">Atomic Labs</a>. We build with agents, then hand you the tools.</sub></p>
