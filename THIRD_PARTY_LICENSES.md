# Third-party licenses

The product name is `puppy`; the official Android application ID is `com.lunixdev.puppyterminal`.

## Android platform APIs and NDK

puppy links against Android platform APIs and NDK system libraries (`liblog`, bionic libc, and the Android C++ runtime). These are supplied by the Android SDK/NDK and are not redistributed as standalone binaries in this repository. Their notices and license terms are provided by the Android SDK/NDK distributions.

## PRoot (Android runtime)

The packaged Android ARM64 and x86_64 PRoot binaries and relocatable loader were built by the upstream `green-green-avk/build-proot-android` project at commit `01f83b8841358450c78333d1b33ab30d4943bec4`. The PRoot source is `third_party/source/proot-v0.15_release.tar.gz` (SHA-256 `d678603aa80aea84969d2897d2a4f98acc8f6008defca960e40ce4ad4e8e893e`) and is licensed GPL-2.0-or-later. Its Android build scripts are included in `third_party/source/build-proot-android-01f83b8.tar.gz` (MIT, SHA-256 `1d2460884c8427687e3eb9b3ee25e2c58c2a10d608cf6b57f8ae9cb8a8715f56`). The upstream binary archive hashes were `9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281` for aarch64 and `f7ecbd4ee1041eefc2d843aa9c080a583fd6c3bb010b3bb7b7522ba05e06bf95` for x86_64.

The PRoot executable statically includes talloc 2.1.14. Its complete source archive is `third_party/source/talloc-2.1.14.tar.gz` (SHA-256 `b185602756a628bac507fa8af8b9df92ace69d27c0add5dab93190ad7c3367ce`), under LGPL-3.0-or-later. The corresponding upstream license notice is in the source archive.

## Alpine Linux bootstrap

puppy bundles the Alpine 3.22.6 minirootfs archives in the APK. Pinned archive SHA-256 values are aarch64 `821565fa8f3953eefd12497b166b4b50add2f7c57fb312e75862f5867e06fefe` and x86_64 `27694aaa55fd7a9e3ef596e0ad4eb66802308bb20172b17030cd5f4d8ae9bac2`. The minirootfs includes Alpine packages with their own notices/licenses; packages installed later remain subject to their upstream licenses.

## JUnit 4.13.2 (test scope)

JUnit is fetched by Gradle for local unit tests and is not packaged in the application. JUnit is licensed under EPL-1.0. See <https://junit.org/junit4/license.html>.
