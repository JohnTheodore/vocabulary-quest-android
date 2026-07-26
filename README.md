# Evidence Based Vocabulary Android Wrapper

![Version](https://img.shields.io/badge/version-0.9.9-blue.svg)

A streamlined Android application that provides a native container for the Evidence Based Vocabulary online learning platform.

## Visual Assets

### Launcher Icon & Splash Logo
| Launcher Icon | Splash Logo |
| :---: | :---: |
| ![Launcher Icon](app/src/main/res/drawable/logo.png) | ![Splash Logo](app/src/main/res/drawable/splash.png) |

## Overview

This project is a WebView-based Android application designed to provide a seamless experience for Evidence Based Vocabulary users on Android devices.

## Recent Stability Improvements (v0.9.9)
*   **Automatic Renderer Recovery:** Implemented `WebViewRenderProcessClient` to detect and automatically recover from hung renderers (UI freezes).
*   **Renderer Crash Resilience:** Centralized lifecycle management ensures that renderer crashes or system kills are handled gracefully without requiring an app restart.
*   **Safe Lifecycle Handling:** Replaced destructive screen-off behavior with proper pause/resume logic. JavaScript timers are paused and media is suspended without blanking the page.
*   **TTS Generation Safety:** Added generation-based tracking for Text-to-Speech callbacks to prevent stale utterances from targeting detached WebView instances.
*   **Popup Management:** Every popup WebView now correctly handles renderer loss, preventing application-wide crashes.
*   **Diagnostics:** Improved logging for WebView provider versions and recovery incidents.

## Key Features
*   **Native Keyboard Bridge:** Automatically triggers the Android soft keyboard when spelling inputs appear.
*   **Native Speech Synthesis Bridge:** Implements a custom `AndroidSpeechSynthesis` bridge using native Android TTS.
*   **Immersive Learning:** Interaction lockdowns prevent accidental zooming or text selection.
*   **Modern Android UI:** Built with **Jetpack Compose** and **Material 3**.

## Build Requirements
*   Android Studio Ladybug or newer.
*   Android SDK 34+.
*   Gradle 8.0+.

## How to Build
1. Clone the repository.
2. Open the project in Android Studio.
3. Run the `:app:assembleDebug` task.

## License
MIT License
