# Evidence Based Vocabulary Android Wrapper

![Version](https://img.shields.io/badge/version-0.9.10-blue.svg)

A streamlined Android application that provides a native container for the Evidence Based Vocabulary online learning platform.

## Visual Assets

### Launcher Icon & Splash Logo
| Launcher Icon | Splash Logo |
| :---: | :---: |
| ![Launcher Icon](app/src/main/res/drawable/logo.png) | ![Splash Logo](app/src/main/res/drawable/splash.png) |

## Overview

This project is a WebView-based Android application designed to provide a seamless experience for Evidence Based Vocabulary users on Android devices.

## Current Version: 0.9.10
* **Spelling keyboard recovery:** Confirms the soft keyboard opened before treating a card as handled, retries a deferred request after resume, and leaves a deliberately dismissed keyboard closed.
* **Installation note:** The v0.9.10 GitHub APK has a different debug signing key from v0.9.8 and v0.9.9. Android requires uninstalling those versions before installing v0.9.10; uninstalling clears app-local data and sign-in state.
* **Lesson-scoped screen policy:** Keeps the display awake during an active lesson, then restores the device's normal screen timeout on completion.
* **Stall fix:** Added Activity-to-WebView lifecycle forwarding so kiosk sessions don't enter power-save mid-exercise.
* Interaction lockdown: Disabled pinch-zoom, long-press selection, and context menus.
* Updated main URL to `evidencebasedvocabulary.com`.
* Native Speech Synthesis bridge implementation.
* Custom adaptive launcher icon and splash screen.
* Full-screen (Edge-to-Edge) implementation.
* Configuration change fixes for external keyboards.

## Key Features

*   **Native Keyboard Bridge:** Automatically triggers the Android soft keyboard when spelling inputs appear, improving the "no-tap" experience for students.
*   **Native Speech Synthesis Bridge:** Implements a custom `AndroidSpeechSynthesis` bridge that polyfills the web `speechSynthesis` API using native Android TTS, ensuring that lesson narrations work reliably.
*   **Audio Context Management:** Includes specialized logic to handle and resume `Howler.js` and Web Audio contexts, bypassing standard browser restrictions on autoplay audio.
*   **Immersive Learning:** Interaction lockdowns prevent accidental zooming or text selection during gameplay.
*   **Modern Android UI:** Built with **Jetpack Compose** and **Material 3**, featuring full **Edge-to-Edge** support for an immersive learning environment.
*   **Performance Optimized:** Utilizes software rendering for the WebView to ensure consistent input focus and backspace behavior.

## Project Structure

*   `MainActivity.kt`: The main entry point using Compose to host the `WebView`.
*   `AndroidSpeechSynthesis.kt`: Native implementation of the Text-to-Speech bridge.
*   `res/`: Optimized resources including an adaptive launcher icon specifically tuned for the Pixel Tablet experience.

## Build Requirements

*   Android Studio Ladybug or newer.
*   Android SDK 34+.
*   Gradle 8.0+.

## How to Build

1. Clone the repository.
2. Open the project in Android Studio.
3. Run the `:app:assembleDebug` task or click the **Run** button in Android Studio.

## GitHub APK signing

GitHub release APKs use the debug build and the key stored locally in
`.local-signing/debug.keystore`. Gradle reads its credentials from the
gitignored `.local-signing/debug.properties`. Keep both files together when
moving the release build to another machine. Before uploading a future APK,
compare its signer certificate with the previous GitHub release; a different
certificate prevents an in-place Android update.

## License

MIT License
