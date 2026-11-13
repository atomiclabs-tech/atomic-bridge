# Security policy

## Supported versions

| Version | Security support |
| --- | --- |
| 1.0.1 | Supported |
| Earlier versions | Update to 1.0.1 |

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/atomiclabs-tech/atomic-bridge/security/advisories/new)
for a suspected vulnerability. Do not open a public issue with exploit details,
credentials, pairing data or phone content. Include affected versions, a minimal
reproduction and the impact, with private values replaced by placeholders.

Maintainers aim to acknowledge reports within seven days and provide a progress
update at least every two weeks while investigating. We coordinate a fix and
public disclosure with the reporter once the impact and remedy are understood.

## Threat model

Atomic is intended for a dedicated Android 14+ phone under its owner's control.
The phone grants extensive capabilities through accessibility and Android
permissions. The relay's admin credential authorizes full remote control of the
phone: anyone who obtains it must be treated as having that control. Protect the
Mac account, credential files, saved phone session and any account used by the
Bitwarden helper.

Admin routes require the admin credential and a loopback connection. Pairing
credentials are short-lived and single use. The phone accepts cleartext relay
connections only to private literal addresses; use an encrypted connection for
other destinations. These controls do not protect against a compromised Mac,
a compromised phone or an untrusted process running as the same Mac user.

Keep secrets and phone data out of issues, logs and screenshots. Use a dedicated
test installation and synthetic data when reproducing a security problem.
