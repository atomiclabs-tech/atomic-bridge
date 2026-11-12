# Type a Bitwarden field into the phone

`tools/type_secret.py` reads one vault field locally and sends it straight to
Atomic Bridge's focused input field. **The secret never passes through the agent,
chat, argv, logs or tool output.** Run it yourself in a trusted terminal; do not
paste a password or vault session into a tool call or conversation.

## Requirements

- The Bitwarden CLI (`bw`) on your Mac and a Bitwarden account on bitwarden.com
  or a self-hosted server. Set `BW` if the executable is not on `PATH`.
- Atomic Bridge installed and paired, its relay running, and the local plugin admin
  credential configured. See the [operator guide](OPERATOR.md).
- A phone input field that accepts text through accessibility. Focus it before
  typing. The helper does not pick the field for you.
- The checkout's Python environment, prepared with `tools/mac-venv.sh`.

The helper uses your CLI account and configuration. It does not supply an
account name, server or vault data directory. Set `BITWARDENCLI_APPDATA_DIR`
only when you deliberately use a separate CLI profile.

## Select the server and sign in

For the default bitwarden.com cloud account, keep the CLI's default server.
If this CLI profile previously used another server, select the cloud server
before login:

```sh
bw config server https://vault.bitwarden.com
```

For a self-hosted account, configure your own HTTPS vault URL instead:

```sh
bw config server https://vault.example.com
```

Use the URL for your account's region or installation. If the CLI refuses a
server change while signed in, log out before changing it, then sign in again.
Check the selected server with `bw status`; do not share its raw JSON because
it can include account details.

```sh
bw login
```

Complete any account authentication prompts locally. Unlock through one of the
methods below so the session output is captured privately for the helper.
Avoid debug tracing (`set -x`), terminal recording and commands that print a
session into a shared transcript.

## Choose a session method

### Caller-owned session with BW_SESSION

Capture an unlock session without printing it:

```sh
export BW_SESSION="$(bw unlock --raw)"
tools/py.sh ../tools/type_secret.py --check
```

The helper passes this value only in child process environments and leaves
this session unlocked. When finished, lock the vault and clear the variable:

```sh
bw lock
unset BW_SESSION
```

An explicitly empty `BW_SESSION` is an error. If it is set, it takes precedence
over `ATOMIC_BW_SESSION_CMD`, so unset it before choosing the next method.

### Helper-owned session with ATOMIC_BW_SESSION_CMD

Set `ATOMIC_BW_SESSION_CMD` to a command that writes only a fresh session on
stdout. The helper captures that output privately and locks the vault after
use, including after a failed phone operation. It parses shell-style quoting
but executes the command without a shell: pipes, `$HOME` and substitutions in
the variable are not expanded. Use an executable script for multiple steps.

For example, use macOS Keychain Access to create a password item named
`atomic-bitwarden-unlock` containing your vault unlock password. Add the item
through the app, so the password never appears in shell history or argv. Give
access only to the programs you trust. Save this local script as
`~/.local/bin/atomic-bw-session`:

```sh
#!/usr/bin/env bash
set -euo pipefail
export BW_PASSWORD="$(/usr/bin/security find-generic-password -s atomic-bitwarden-unlock -w)"
exec "${BW:-bw}" unlock --passwordenv BW_PASSWORD --raw
```

Make it private and configure the helper, without running the session script
on its own:

```sh
chmod 700 ~/.local/bin/atomic-bw-session
unset BW_SESSION
export ATOMIC_BW_SESSION_CMD="\"$HOME/.local/bin/atomic-bw-session\""
```

The keychain lookup can ask for local approval. Its output and the resulting
vault session stay inside the helper's subprocesses. Never put the password,
session or a command that prints other output into the environment setting.

## Check the setup

From the checkout:

```sh
tools/py.sh ../tools/type_secret.py --check
```

This does not retrieve a vault field or acquire a session. It runs
`bw --version`, reads only the server and vault state from `bw status`, and
calls `android_ping`. It reports the CLI path/version, server, vault state and
`Phone: online` or a fixed offline reason; it never prints account email or
other vault status fields.

Exit 0 means the phone is online and the vault is unlocked, or locked with a
session method configured. A configured command is not executed or verified
by this check. Otherwise exit 1 names the first missing step, such as login,
server configuration or setting `BW_SESSION` / `ATOMIC_BW_SESSION_CMD`.

## Type a field

Open the target app and focus its input field on the phone, then run locally:

```sh
tools/py.sh ../tools/type_secret.py "<item>" --field password
```

The default field is `password`; `username` and `totp` are also supported. The
helper syncs the vault, retrieves the chosen field and calls `android_type`.
It clears the focused field first; add `--no-clear` only when you intend to
append. Empty fields and failed calls return a nonzero exit. The command prints
a redacted tool result, never the retrieved field or session.

To find the right item, list matching names without retrieving login fields:

```sh
tools/py.sh ../tools/type_secret.py "<search>" --list
```

Item names are private too. Review this output locally and do not paste it into
chat or shared logs. `--list` uses the same session ownership and cleanup rules.
The helper never retries a phone action automatically.

## Troubleshooting

- **CLI missing or wrong profile:** install `bw` or set `BW` to its executable.
  Check that `BITWARDENCLI_APPDATA_DIR` points to the intended CLI profile when
  you use one.
- **Wrong server or unauthenticated:** select the correct cloud region or
  self-hosted URL with `bw config server`, then run `bw login`. Do not share raw
  status output.
- **Locked vault or missing session:** obtain a fresh `BW_SESSION` or configure
  the session command. Clear an empty or expired `BW_SESSION` before trying the
  command method. A helper-owned session is locked after each invocation.
- **Keychain or unlock failure:** review the named item and local access prompt.
  A session command must emit only the session and exit successfully. Do not
  debug it by printing its stdout.
- **Phone offline or text in the wrong place:** reconnect Atomic Bridge, run `--check`
  and focus the intended field. Some apps expose no editable accessibility
  field. Review the screen locally before retrying, since an uncertain typing
  result is not safe to repeat blindly.
- **Cleartext warning:** without Tailscale, a home-network address works only
  on the Mac's local network and is unencrypted; anyone there could read it.
  Tailscale is optional and recommended for encrypted access from other
  networks. Your own HTTPS proxy or tunnel using a `wss://` address is possible,
  but exposes the relay to the internet and is not guided or supported in v1.
  The helper's secrecy guarantee does not encrypt a cleartext transport.
