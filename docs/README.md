# QuicLoc Documentation

QuicLoc is an Android utility that lets pre-approved contacts request your GPS location by sending a trigger word over SMS or any messaging app's notifications. It responds automatically, even when the screen is off.

This directory is the engineering reference. The user-facing README at the project root is the place to point users; the docs here are for anyone working on the code.

## Index

| Doc | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System overview, component map, data flow for the three trigger paths |
| [SECURITY.md](SECURITY.md) | Threat model, encryption layers, biometric gate, auth boundaries |
| [BACKUP.md](BACKUP.md) | PIN-encrypted backup blob format, snapshot debouncing, restore flow, error categories |
| [TRIGGER-FLOW.md](TRIGGER-FLOW.md) | What happens between "loc" arriving and the location reply being sent, per trigger source |
| [LOCKDOWN.md](LOCKDOWN.md) | Find-my-phone passphrase, panic mode, Device Admin vs cover-screen fallback |
| [PERMISSIONS.md](PERMISSIONS.md) | Every permission, why we need it, when we ask for it, what changes on Android 13/14 |
| [TESTING.md](TESTING.md) | Manual test plans for the things unit tests can't reach |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Dev setup, build commands, project layout, conventions |

## See also

- Root [README.md](../README.md) — user-facing pitch and setup
- [DECLARATIONS.md](../DECLARATIONS.md) — Play Console submission text for sensitive permissions
- [PRIVACY_POLICY.md](../PRIVACY_POLICY.md) — published privacy policy
