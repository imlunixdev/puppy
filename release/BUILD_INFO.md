# puppy Android build

- Application: puppy
- Application ID: `com.lunixdev.puppyterminal`
- Version: 0.1.0 (version code 1)
- Build type: debug
- Android SDK: minimum 26, target 35
- Supported architectures: arm64-v8a, x86_64
- Signing type: standard Android debug certificate
- Artifact filename: `puppy-release.apk` (debug-signed build)
- Git commit: unavailable (workspace has no Git metadata)
- Build date: 2026-10-04
- Validation: JVM unit tests passed; `lintDebug` passed; `assembleDebug` passed
- PTY: native Android pseudo-terminal bridge
- Linux userspace: PRoot and bundled Alpine Linux 3.22.6 base filesystems
- Interactive prompt: POSIX/Bash/Zsh/Fish startup hooks; Fish uses native highlighting and suggestions
- GPU acceleration: Android Canvas acceleration where available; CPU terminal state/parser with platform fallback
- Blur: not implemented

Android application ID changed from `dev.puppy.terminal`. Android treats this as a separate app and does not migrate the earlier app's private data automatically.
