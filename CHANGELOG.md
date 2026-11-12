# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
