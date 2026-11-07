# Contributing

Thanks for helping! Bug reports from **other Legion models** are the most useful thing right now.

## Reporting a bug

1. LegionDock → **System → Diagnostics → Copy**.
2. [Open an issue](https://github.com/ttabouch12/LegionDock/issues/new) with:
   - tablet model (e.g. TB321FU) and firmware (Settings → About tablet)
   - what you did, what you expected, what happened
   - the diagnostics text

## Building

Requirements: JDK 17, Android SDK with platform 36, Gradle 8.13+.

```
gradle :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without signing variables the release APK is signed with your debug key, which is fine for testing.
To check the shell backend: `sh -n app/src/main/assets/core.sh`.

## Code layout

See [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md). Short version:

- Java UI and services in `app/src/main/java/io/legiondock/`, no external libraries
- privileged logic in `app/src/main/assets/core.sh`; every change it makes must be saved first so undock can put it back
- device-specific behaviour behind a capability check (`core.sh caps`), never assumed

## Pull requests

- One topic per PR, tested on a real tablet (say which).
- Keep `core.sh` POSIX `sh` (it runs on Android's `mksh`/toybox).
- Anything that changes a system setting must also restore it in `restore_state` / `restore_all`.

## Releases (maintainers)

Releases are published by the **Release** workflow when a `v*` tag is pushed. Add release notes in `docs/release-notes/<tag>.md` first.

For signed releases, add two repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 legiondock-release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore / key password (key alias `legiondock`) |

Every release must be signed with the same key, or Android refuses to update the installed app.
