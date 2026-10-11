# Third-party notices

Orca Pocket is an independent application inspired by the architecture of [Orca](https://github.com/stablyai/orca), MIT license, Copyright (c) 2026 Lovecast Inc. The upstream repository was not modified. Its license is included in the APK assets.

Runtime dependencies:

- mwiede JSch 0.2.26 — BSD-style license; bundled license includes the applicable JZlib and jBCrypt notices.
- Bouncy Castle bcprov-jdk18on 1.77 — MIT-style license, Copyright (c) The Legion of the Bouncy Castle Inc.
- Geist font — SIL Open Font License 1.1, Copyright 2024 The Geist Project Authors; font file sourced from Orca's renderer assets.
- Tabler Icons v3.34.1 - MIT license, official `tabler/tabler-icons` outline SVG assets; license bundled at `assets/icons/LICENSE.txt`.
- Android platform / system WebView — supplied by the device; not redistributed with this APK.

License texts are in `android-app/app/src/main/assets/licenses/` and are packaged inside the APK. The desktop service bundles org.json 20240303 (public domain); Android uses its built-in org.json implementation.

The build toolchain (Android SDK and JDK), native test instrumentation, SSH test keys, signing keys, and emulator images are development resources, excluded from the source delivery and release APK.

- OpenMuse — MIT license; adapted mobile chat interactions and unmodified browser worker sources. Notices are bundled in `assets/LICENSE-OpenMuse` and `desktop-service/browser/LICENSE-OpenMuse`.
- markdown-it 14.1.0 — MIT license; browser distribution and license bundled in Android assets.
- Playwright — Apache 2.0; optional computer browser dependency installed by npm, not embedded with Chromium in the APK.
