Third-Party Notices
===================

This file is the central notice list for third-party code and packaged
dependencies used by RADEXCVR.

Included Components
-------------------

- RADE Modem
  - Source: native RADE reference implementation under `app/src/main/cpp/rade`
  - Upstream: `radae_nopy`
  - Author: Peter B Marks
  - License: BSD 2-Clause

- Opus / FARGAN Vocoder
  - Source: native codec and neural vocoder code under
    `app/src/main/cpp/opus`, `app/src/main/cpp/celt`,
    `app/src/main/cpp/silk`, and `app/src/main/cpp/dnn`
  - Upstream: Xiph.Org / Opus and related FARGAN components bundled through the
    RADE export
  - Author: Xiph.Org and respective contributors
  - License: BSD 3-Clause

- FT8CN rig control profiles and CAT implementations
  - Source: imported and adapted Java sources under `app/src/main/java/com/bg7yoz/ft8cn`
    and profile data under `app/src/main/assets/rigaddress.txt`
  - Upstream: FT8CN
  - Repository: https://github.com/N0BOY/FT8CN
  - Author: FT8CN contributors
  - License: MIT

- RADE_decode_Android EOO callsign codec integration
  - Source: EOO callsign codec sources under `app/src/main/cpp/eoo`
  - Upstream: RADE_decode_Android
  - Repository: https://github.com/pepefrog1234/RADE_decode_Android
  - Author: RADE_decode_Android contributors
  - License: MIT

- usb-serial-for-android
  - Source: Gradle dependency `com.github.mik3y:usb-serial-for-android:3.9.0`
  - Upstream: https://github.com/mik3y/usb-serial-for-android
  - Author: mik3y and contributors
  - License: MIT

- Jetpack Compose
  - Source: Gradle dependencies via the Compose BOM and related Compose
    artifacts
  - Author: Google
  - License: Apache 2.0

- AndroidX Libraries
  - Source: Gradle dependencies including `core-ktx`, `lifecycle-runtime-ktx`,
    `activity-compose`, `appcompat`, test libraries, and related AndroidX
    artifacts
  - Author: Google
  - License: Apache 2.0

Notes
-----

- This list intentionally excludes components that are not currently present in
  this repository or Gradle dependency graph.
- Local modifications were made for Android integration, UI wiring, JNI
  binding, and CAT/serial transport integration.
- Original copyright and license notices should be preserved in imported source
  files where present.
