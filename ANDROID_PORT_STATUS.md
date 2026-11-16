# Android 10 wireless head-unit port — development status

This branch adds the first native Android application shell for the MSM8953 ARM64 tablet target.

## Target device

- Android 10 (API 29)
- ARM64 / `arm64-v8a`
- Qualcomm MSM8953
- Tablet reports 5 GHz Wi-Fi support

## What is included now

- Android Gradle application module (Java, minSdk 29).
- Landscape, immersive tablet UI.
- Android 10 runtime permission request for Bluetooth discovery.
- Nearby Bluetooth device discovery.
- Android `startLocalOnlyHotspot` diagnostic to verify that firmware permits an app-requested local hotspot.
- GitHub Actions workflow that assembles a debug APK and uploads it as an artifact.

The hotspot diagnostic reports the band only when Android exposes it in the returned configuration. The application does not force 5 GHz: Android 10's public local-only-hotspot API does not provide a reliable app-controlled band selector on all devices.

## Important: not yet a working Android Auto head unit

This is a buildable hardware-validation shell, not a completed Android Auto receiver. It does not yet implement:

1. Android Auto Bluetooth service advertising, pairing, and protocol handoff.
2. Negotiation of the Wi-Fi session parameters with the phone.
3. The Android Auto TLS authentication/session and channel lifecycle on Android.
4. Video decode/rendering, audio routing/capture, microphone, and touchscreen/input forwarding.
5. Reconnect, lifecycle, and real-phone compatibility testing.

The existing Rust crate is Linux-oriented at the example layer and contains unfinished protocol branches in `src/bluetooth.rs` and `src/lib.rs`. Those must be completed or replaced and then integrated with Android platform APIs. Merely compiling the crate for ARM64 would not provide these Android services.

## Build

GitHub Actions workflow: `.github/workflows/android-tablet.yml`

The workflow uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, compile SDK 35, target SDK 29, and minimum SDK 29. The generated debug APK is an installable prototype for permission and radio testing only.

## Next engineering milestones

- Verify Bluetooth adapter, discoverability, pairing, and hotspot behavior on the actual MSM8953 tablet.
- Implement the Bluetooth transport using Android's Bluetooth APIs and a foreground service.
- Implement/port the wireless handshake and transport with protocol-level tests against a real Android Auto phone.
- Integrate Rust protocol code through JNI only after the Android transport boundary is defined.
- Add hardware-accelerated video output, audio focus/routing, microphone capture, touch/key input, and reconnect.
- Run end-to-end compatibility tests before calling any build an Android Auto head unit.
