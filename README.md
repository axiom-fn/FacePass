# FacePass

**English** | [繁體中文](README.zh-Hant.md)

Use ColorOS face unlock in the system authentication prompt (BiometricPrompt) of the apps you choose.

The ColorOS face sensor reports strength `4095`, which belongs to no Android class, so the system lets no third-party app use face. FacePass lets face pass the eligibility check only for the apps you tick, and only when face suits that particular request. Everything else stays stock.

## Features

- **Whitelist**: face appears only in the apps you tick; every other app keeps stock fingerprint.
- **Per-request judgment**:
  - Requests bound to a Keystore key (with a `CryptoObject`) use fingerprint only. Face is still a weak biometric to the system and cannot unlock such keys.
  - If the same app asks again within a few seconds after face succeeded (common with time-bound keys), that request uses fingerprint only. Turning the screen off clears this record.
- **Older apps**: an app that still verifies through the legacy fingerprint API is carried into the same system prompt, so it gains face alongside fingerprint without being touched itself; if face is not recognised, fingerprint is still there. An app clone borrows the foreground user's face.
- **Prompt panel**: the medium-size ColorOS panel, the same one fingerprint uses. Face and fingerprint start together, and the prompt continues by itself once face succeeds.
- **Early face success**: if your face is already in front of the camera, the result is held until the prompt has finished appearing, so the system does not take it as a cancellation.
- **Fluid Cloud hint**: a hint is shown in Fluid Cloud while face recognition runs.
- **Lock screen**: when the lock screen's own face unlock holds the camera, whitelisted apps can still use face.

## How it works

FacePass injects into no app. Its scope is fixed to System Framework and System UI.

| Where | What |
|---|---|
| system_server | Judges each request (face / fingerprint only / stock) and lets face pass the strength check only for requests judged face; carries a legacy fingerprint request into the prompt; handles the camera held by the lock screen and early face success; posts the Fluid Cloud hint |
| SystemUI | Gives whitelisted apps the medium-size panel, removes the needless confirmation, starts fingerprint together with face |

Face images and matching stay in the hardware and the secure environment (TEE). FacePass sees only success or failure.

## Security

- Face remains a weak biometric to the system: it unlocks no key that requires strong authentication.
- For apps that only check that authentication succeeded, without a key, whether a photo or video can fool face depends on ColorOS face itself. The risk is the same as with face unlock on the lock screen. Leave banking and payment apps unticked unless you need them.

## Requirements

- ColorOS 17 only
- LSPosed with libxposed API 102

## Installation

1. Install the APK, enable FacePass in LSPosed, and make sure the scope includes System Framework and System UI.
2. Reboot.
3. Open FacePass and tick the apps that should use face. Allow notifications the first time the Fluid Cloud hint is used.

Troubleshooting: entries tagged `FacePass` in the LSPosed log show the judgment for each request and, when the system refuses face, the status code.

## Build

```bash
./gradlew :app:assembleRelease
```

For release signing, place `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root. Without it, the debug key is used.

## License

Copyright © 2026 axiom-fn. All rights reserved. No part of this project may be used, copied, modified or distributed without the author's prior written permission. See [LICENSE](LICENSE).

## Author

axiom-fn
