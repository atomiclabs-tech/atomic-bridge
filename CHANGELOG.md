# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 1.0.1 - 2026-11-12

Components: Atomic Bridge app 1.0.1 (versionCode 10), relay 2.4.0, plugin 2.4.0.

### Added

- `android_unlock` and a relay-side PIN file for PIN screen locks, contributed by Xavier (@macurex).

### Fixed

- **Kill link** now keeps the link off on phones without the one-time `WRITE_SECURE_SETTINGS` grant; the status screen says when Android kept screen control on. Reported by Xavier (@macurex).

## 1.0.0 - 2026-11-11

Components: Atomic Bridge app 1.0.0 (versionCode 8), relay 2.3.0, plugin 2.3.0.

### Added

- Guided Mac pairing with address selection, a one-time code, a link and a terminal QR.
- A phone pairing-code field and a link confirmation that shows the full relay address.
- A Bitwarden helper for the configured account, with `--check` to verify access before typing.
- Public setup, tool, operator, contribution and security documentation.
- Automated Mac-side tests and Android unit tests in CI.

### Fixed

- An incorrect relay address fails within about ten seconds and keeps the connection error visible.
- `open_app` recognizes the launched task behind a different app's Activity and reports when foreground verification is unavailable.
