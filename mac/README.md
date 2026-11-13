# Atomic Bridge on the Mac

Atomic Bridge's Python 3.12 relay connects your phone to the 42 `android_*` tools.
The `atomic-bridge` plugin uses only the standard library; the server uses
aiohttp 3.14.3. The phone, relay and plugin keep the Hermes-Relay wire protocol
and session format. See the [operator guide](../docs/OPERATOR.md) for the
complete setup and [tool reference](../docs/TOOLS.md) for the tool inventory.

The caller and plugin run on the **same Mac as the relay**. Admin routes are
loopback-only. The phone connects through Tailscale or the same local network
as your Mac; its session credential cannot authorize local control routes.

## Build and install

Use macOS 14 or later, Python 3.12 and `uv`. From the checkout, prepare the
pinned offline environment and preview installation:

```sh
tools/mac-venv.sh
tools/install-mac.sh --dry-run
```

Run `tools/install-mac.sh` to install. It copies the relay and `pyproject.toml`
to `~/services/atomic-bridge`, creates that service's own `.venv` using the
pinned offline aiohttp package, and copies the plugin to
`~/.hermes/plugins/atomic-bridge`. The required packages must already be in the
local `uv` cache. The installer creates `logs`, `media` and
`~/Library/LaunchAgents/com.atomic.bridge-relay.plist`; it does not load or start
the service. Its final line points you to `tools/pair.sh`.

An unchanged installation reports `Nothing to do.` followed by the same pairing
hint. Stale files are removed only inside the two owned package directories.
Existing plugins, LaunchAgents and the sessions file are preserved.

The installer creates a missing `relay.env` from `mac/.env.example` with mode
0600 and preserves an existing file byte for byte. It creates a missing
`admin.secret` with mode 0600, preserves a valid existing credential and never
prints its contents. Review settings privately before starting the relay.
The dependency fingerprint is saved only after installation and version
verification succeed, so an interrupted installation can be retried.

The LaunchAgent runs the service venv's `python -m atomic_relay serve --env-file
~/services/atomic-bridge/relay.env` with the service directory as its working
directory, `RunAtLoad`, `KeepAlive` and `ThrottleInterval=30`. It supplies only
`HOME` and `PATH`; stdout/stderr go to `logs/launchd.out.log` and
`logs/launchd.err.log`.

## Local authentication and deployment

`ATOMIC_RELAY_ADMIN_SECRET_FILE` and `ATOMIC_BRIDGE_ADMIN_SECRET_FILE` normally
point to `~/services/atomic-bridge/admin.secret`. This must be a regular private
file, mode `0600`, containing one non-empty UTF-8 secret of at least 32
characters. The server reads it at startup; the plugin uses it for every
request and download. Keep credentials out of logs, shell arguments, chat and
git. A caller that can read this file can control the phone; use a dedicated
phone and a trusted Mac account.

Keep a loopback address in `ATOMIC_RELAY_HOST`. It is an explicit,
comma-separated list of literal IP addresses; add the Mac's private address
when the phone connects directly. Hostnames and wildcards (`0.0.0.0` and `::`)
are refused at startup. The server validates every bind before serving. The
plugin and local callers must remain on this Mac. Never expose admin routes
through a reverse proxy.

Atomic Bridge refuses unencrypted addresses outside private networks. The relay
listens only on explicit addresses, requires loopback and refuses wildcard
binds, rather than listening on all networks at once. Forwarding headers never
bypass the loopback admin guard.

To rotate the admin credential, privately replace its file atomically with
mode 0600, then restart the relay through your normal service procedure. The
plugin caches its first read; a 401/403 causes one re-read for the next explicit
call, without retrying the failed action. Coordinate other local readers.
Empty admin-secret configuration is for isolated health/tests only: startup
warns and all admin routes refuse requests.

The public `/health` body contains exactly `status`, `version` and
`uptime_seconds`. A valid admin bearer adds `clients`, `sessions` and
`phone_connected`; a wrong bearer still gets only the public fields.
`/bridge/devices` and control/status routes require the admin bearer. Keep
cached phone data private. Admin requests refuse any `Origin` header, require
`application/json` for body-bearing methods and refuse loopback requests with
forwarding headers.

The phone's session bearer authorizes only its WebSocket and media traffic;
it cannot register pairing, select devices or invoke local admin routes.
Stored session expiries and grants are unchanged on load. Atomic Bridge recovers its
connection after crashes and reboots. The one-time `WRITE_SECURE_SETTINGS`
grant enables accessibility self-heal; **Kill link** stays off until **Turn
link back on**. Without the grant, Android may keep accessibility enabled,
but the link stays off and the status screen says when screen control remains
on. Android may expose sensitive app content through accessibility.

`android_unlock` reads the PIN from a private file on this Mac for each call;
the tool has no PIN argument, and the PIN never passes through the agent,
chat, argv or tool output. Configure the optional
`ATOMIC_RELAY_UNLOCK_PIN_FILE` using [Unlock with a PIN](../docs/OPERATOR.md#unlock-with-a-pin).
Use unlock only over Tailscale or loopback: the PIN travels in cleartext on
`ws://`, while Tailscale encrypts that private-IP transport.
It supports PIN locks only, with one entry per call; a wrong PIN spends a real
lockout attempt. Nothing runs before the first local unlock after a reboot.

## Run, pair and status

After installation, run the guided command from the checkout:

```sh
tools/pair.sh
```

It uses `~/services/atomic-bridge/relay.env` when present, otherwise `relay.env`
in the working directory. Use `--env-file <path>` to select a different file.
It checks loopback health, offers to start the installed relay if needed, and
asks for approval before changing relay settings or restarting its service.
It never restarts the gateway. macOS may ask whether the relay's Python can
accept incoming connections; allow the connection on your trusted network.

The command first uses `ATOMIC_RELAY_PHONE_URL`, then an existing non-loopback
listener address. Only when neither supplies the destination does it choose a
new private IPv4 bind candidate: `--address <private-ipv4>` supplies that
candidate instead of discovery; without it, you choose a discovered tailnet
or LAN address. Relay setup asks for confirmation unless you pass `--yes`.
Direct connections require that address in the host list. Approved setup
preserves other env lines and makes a dated `relay.env` backup; saving the same
address is a no-op.

Tailscale is optional and recommended. With Tailscale on both the Mac and
phone, choose the Mac's tailnet address: the phone reaches the relay from home
Wi-Fi, other Wi-Fi or cellular, and Tailscale encrypts the link.

Without Tailscale, choose a home-network address only while both devices are
on the same local network. That link is unencrypted, so anyone on that network
could read it; guided pairing warns about this. It stops working when the
phone leaves that network.

Other remote setups through your own HTTPS reverse proxy or tunnel are possible
because the app accepts any `wss://` address. They expose the relay to the
internet and are not guided or supported in v1.

The terminal prints the relay address, a one-time code, a deep link and a QR.
These are private: do not copy them into logs, chat or screenshots. On the
phone, scan the QR or open the deep link, review the full relay address and tap
**Pair**. Cancel is the default. Alternatively, enter the address and code in
Atomic Bridge and tap **Pair**. The phone does not generate a pairing code.

A code is single use and expires after 10 minutes. The command waits up to
590 seconds and prints `Paired` only after registration has been consumed and
a phone is connected. `--no-qr` omits the QR; `--no-wait` returns after
registration without claiming a connection. New sessions default to 30 days;
`--ttl never` explicitly requests a non-expiring session. Durations also accept
seconds or `s/m/h/d/w/y`. Existing stored sessions and grants stay intact;
trusted-device refresh extends expiry to 180 days after a successful refresh.

Pair exit codes are 0 for paired or `--no-wait`, 1 for invalid arguments or
refused registration, 2 for unreachable relay or declined setup, and 3 when no
phone pairs within the wait. Use `tools/pair.sh --help` for all options.

For a foreground relay, stop any competing instance first and run from the
installed service directory:

```sh
cd ~/services/atomic-bridge
.venv/bin/python -m atomic_relay serve --env-file relay.env
```

Serve defaults to its working-directory `relay.env`; an explicit path must
exist. Process `ATOMIC_RELAY_*` variables override the env file. Startup checks
settings, sessions, admin credential, storage and every listener before serving.
Empty sessions-file configuration is only for isolated health/tests.

To check the connection without printing cached phone data:

```sh
tools/py.sh -m atomic_relay status --env-file ~/services/atomic-bridge/relay.env >/dev/null
```

Status exits 0 for connected, 2 for no connected phone and 1 for an unreachable
relay or invalid response. Use `--port <port>` when configured differently.
The [Bitwarden helper](../docs/BITWARDEN.md) types a private vault field into
the phone without putting it into chat.

## Reversible upgrade

Preview both directions, then upgrade from the checkout:

```sh
tools/install-mac.sh --dry-run
tools/py.sh ../tools/upgrade.py --dry-run
tools/py.sh ../tools/upgrade.py --rollback --dry-run
tools/py.sh ../tools/upgrade.py
```

The installer dry-run inspects installed files; upgrade dry-runs render pure
plans. A real upgrade verifies the installation and relay health, then backs
up the relay package, pyproject, env and plugin under
`~/services/atomic-bridge/backups/<UTC timestamp>/`. A private
`upgrade-state.json` records progress. A verified started backup is reused
after interruption. Only after backup completes does the upgrade create a
missing secret and update its own env settings, preserving a valid explicit
host list and all unrelated lines/comments. It installs offline and reloads
only the relay, checking health for up to 60 seconds and credentialed phone
status for up to 120 seconds.

A failure stops with the failed step and a rollback command:

```sh
tools/py.sh ../tools/upgrade.py --rollback --backup '<PRINTED_BACKUP_DIRECTORY>'
```

Before a backup is complete the script says no usable backup exists. Without
`--backup`, rollback selects the newest verified snapshot. It restores all
four backed-up parts and the saved env, reloads the relay and checks the saved
version. Its phone probe uses the restored env's explicit admin credential;
a backup without that setting uses the legacy unauthenticated probe. Review
any nonzero exit before treating recovery as complete.

The scripts never restart or signal the gateway, edit the watcher or the
host's env, rewrite phone sessions, or delete another plugin. When a plugin
update needs a gateway restart, the script prints the manual operator step.
Run `hermes gateway restart` from a separate shell when ready. A running
session's tool inventory is fixed until rebuilt; `/reload-mcp`, `/new` or
context compaction refreshes it. Confirm `android_ping` in the intended session.
For app updates, retain your signing key and install the release over the old
app; see [update and rollback](../docs/OPERATOR.md) for the full procedure.

## Logs and watcher line format

`logs/relay.log` uses `YYYY-MM-DD HH:MM:SS,mmm LEVEL logger: message`, rotates at
10 MiB with five backups and sends WARNING and above to stderr. aiohttp access
logging is off. `ATOMIC_RELAY_LOG_LEVEL=DEBUG` enables relay debug logging.
A watcher must follow both `--log ~/services/atomic-bridge/logs/relay.log` and
`--log ~/services/atomic-bridge/logs/relay.log.1` to span rotation. Logger names
are `atomic_relay` and `atomic_relay.bridge`; match these exact message formats:

```text
Auth failed
WebSocket from blocked IP %s — rejecting
bridge: detached device %s (%s)
```

The watcher status source remains `/bridge/devices`, using the local admin
bearer. Existing session storage remains `~/.hermes/hermes-relay-sessions.json`
with the upstream version 1 format. Keep its contents and raw device responses
private. The installer and upgrade do not modify an external watcher.

## Media and notification boundaries

Local media registration is limited to `ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS`
(default `~/.hermes/workspace`), with credential/system locations denied and
no-follow checks across every path component. There is no automatic system-temp
root. Phone uploads are separate: only owned `atomic-upload-*` files directly
inside `ATOMIC_RELAY_MEDIA_DIR` qualify for upload validation; that directory
is not automatically a general registration root. An outside-root push returns
an error and a hint naming the setting and default, without exposing its value.

With Notification access, posted and removed contents travel from phone to
relay before filtering. `ATOMIC_RELAY_NOTIFY_ALLOW` and
`ATOMIC_RELAY_NOTIFY_DENY` use exact package names or `prefix.*` patterns.
Deny wins; empty allow keeps everything not denied, otherwise allow must match.
Invalid patterns stop startup. Filtering precedes buffering and waiter
publication for posted and removed events. It does not redact content in
transit. Notification text and content-bearing matches are never logged.

Recent notifications use `posted_at` for posts and `removed_at` for removals.
A nonempty `text` filter searches posted title/text/sub-text and excludes
removal-only rows; omit it for removal history. `android_wait_notification`
returns only new matching posted events.

Tool results can include `waited_ms`, `foreground_verified`, `truncated`,
`visited`, `hold_ms` and `hint`. App opening checks for the launched package;
if a foreign Activity is in front but the launched task is visible, a success
can carry `foreground_verified:false` with a hint. Without accessibility,
a launch can also succeed without foreground verification. Tree commands read
fresh accessible content; text matching uses NFKC, whitespace and case
normalisation. Searches stop at 20,000 nodes or 20 seconds and report coverage.
Pages absent from the accessibility tree may time out. Held drag accepts
`hold_ms` from 0 to 3000 with movement `duration` mapped to `duration_ms`.

Saving a changed relay address retargets the connection without rewriting the
stored session; an unchanged address is a no-op. A cancelled deep link changes
nothing, and a newly confirmed pairing replaces credentials only after
successful authentication. Socket opening is bounded to about 10 seconds;
connection failures remain visible until the next relevant action.

## Configuration reference

`mac/.env.example` is the defaults reference. Keep `relay.env` and any plugin
environment file private, outside git. Hermes must receive plugin variables in
its own process environment (its private `.env`); the plugin does not read the
relay's env file. Do not print either environment or dump the sessions file.
The operator check URL uses the example's loopback `ANDROID_BRIDGE_URL` host
and the installed `relay.env` port; it refuses credentials, redirects and
non-loopback destinations.

| Variable | Default / meaning |
| --- | --- |
| `ATOMIC_RELAY_HOST` | `127.0.0.1`; comma-separated literal IP addresses, loopback required, wildcards refused; add this Mac's tailnet or LAN address for the phone |
| `ATOMIC_RELAY_PHONE_URL` | empty; optional ws/wss/http/https URL with a host and optional port only, no path/query/fragment/credentials; cleartext requires a private, CGNAT, link-local or loopback literal, hostnames require wss/https; normalizes to ws/wss and preserves optional ports |
| `ATOMIC_RELAY_PORT` | `8767`; use `0` only for isolated tests |
| `ATOMIC_RELAY_SESSIONS_FILE` | `~/.hermes/hermes-relay-sessions.json`; empty means in-memory tests |
| `ATOMIC_RELAY_ADMIN_SECRET_FILE` | `~/services/atomic-bridge/admin.secret`; regular private file (mode `0600`), at least 32 characters; empty refuses all admin routes with a startup WARNING for isolated health/tests; replace the file and restart the relay to rotate |
| `ATOMIC_RELAY_UNLOCK_PIN_FILE` | empty; optional path to a regular private file (mode `0600`), owned by the relay's user, not a symlink, at most 1 KiB, holding the phone's lock-screen PIN, 4 to 16 digits; read on every `android_unlock`; when set it is validated at startup |
| `ATOMIC_RELAY_TRUSTED_PROXIES` | empty; comma-separated proxy IP addresses; only listed transport peers may supply client/TLS forwarding headers; use the right-most untrusted client address, or the peer if all hops are trusted |
| `ATOMIC_RELAY_NOTIFY_ALLOW` | empty; comma-separated exact package names (`[A-Za-z0-9_.]+`) or `prefix.*` patterns; empty keeps all unless denied, otherwise requires a match; invalid patterns fail startup |
| `ATOMIC_RELAY_NOTIFY_DENY` | empty; same pattern syntax as allow; a match drops posted and removed events before buffering or waiter publication, taking precedence over allow |
| `ATOMIC_RELAY_LOG_FILE` | `~/services/atomic-bridge/logs/relay.log` |
| `ATOMIC_RELAY_LOG_LEVEL` | `INFO`; DEBUG/INFO/WARNING/ERROR/CRITICAL |
| `ATOMIC_RELAY_MEDIA_DIR` | `~/services/atomic-bridge/media`; private upload storage |
| `ATOMIC_RELAY_MEDIA_MAX_MB` | `256`; transfer size limit |
| `ATOMIC_RELAY_MEDIA_TTL_SECONDS` | `86400`; token lifetime |
| `ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS` | `~/.hermes/workspace`; colon-separated registration roots; uploads use `ATOMIC_RELAY_MEDIA_DIR` |
| `ATOMIC_RELAY_SERVER_VERSION` | package version `2.4.0`; health/auth version |
| `ANDROID_BRIDGE_URL` | HTTP IPv4-loopback URL on port `8767`; exact executable default in `.env.example`; plugin also accepts localhost, while operator settings require a literal loopback address |
| `ATOMIC_BRIDGE_ADMIN_SECRET_FILE` | `~/services/atomic-bridge/admin.secret`; admin bearer for every plugin request and download |
| `ANDROID_BRIDGE_TIMEOUT` | `30`; request timeout in seconds |
| `ATOMIC_BRIDGE_PULL_DIR` | `~/.hermes/workspace/phone-files/`; pulled attachments |
| `BW` | `bw` on `PATH`; vault CLI executable for `tools/type_secret.py` |
| `BW_SESSION` | unset; use the caller's session as is, passed only in the child environment |
| `ATOMIC_BW_SESSION_CMD` | unset; required when `BW_SESSION` is unset; quoted command line executed without a shell; helper locks only sessions it obtains |
| `BITWARDENCLI_APPDATA_DIR` | unset; passed through only when the caller sets it; no account/path default |

The admin credential is cached after its first file read. A 401/403 re-reads
the file once, without retrying the tool action. Share sanitized command/check
failures only; never share raw device responses, credentials or phone content.

## What changed from Hermes-Relay

The relay is an independent Python service and the phone plugin uses only the
standard library. The original 35 Android tools retain their schemas apart
from the optional `android_drag.hold_ms` addition; seven tools add notifications,
file transfer, the call-audio probe and PIN unlock. Desktop and dashboard
surfaces are not registered. Session/grant persistence and wire routing remain compatible.
Local relay logging replaces central CLI logging. The installer preserves
existing installations; the operator controls gateway restarts and session
refreshes. Upstream attribution is retained in [NOTICE](../NOTICE).
