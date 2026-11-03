# Doppler Roller

Android utility app for securely managing a Doppler service account token used by Hermes Agent.

## What it does

- Stores your Doppler personal token in Android EncryptedSharedPreferences (Android Keystore-backed)
- **Create Token** — creates a fresh service account token on Doppler, stores it locally, status goes green
- **Copy Token** — copies the live token to clipboard to hand to Hermes
- **Delete Token** — immediately revokes the token on Doppler and clears it from the device, status goes red

No live token exists at rest. You control exactly when a valid token exists.

## Setup

1. Sideload the APK (enable Install unknown apps in Android settings)
2. First launch: paste your Doppler personal token and tap Save Securely
3. Use Create / Copy / Delete as needed per session

## Build

Requires Android SDK 34 and Java 17.

```bash
./gradlew assembleDebug
```
