# UI Bug Report

## Summary
- User-visible symptom: App shows "Hello World"/blank WebView then closes.
- Severity (A/B/C/D/E): B

## Repro Steps
1. Build and run the Android app.
2. Observe the default hello-world screen and/or a blank WebView.
3. App closes shortly after launch.

## Signals
- WebView console: Not visible before fix.
- Android logs: AppCompat theme crash risk in Android module; wrong module wiring.
- Network/asset errors: Web UI never served because the WebView module was not wired.

## Root Cause
- What broke: The Gradle settings pointed :app to the placeholder module, not the WebView module in `android-app`.
- Why it broke: The real Android app and assets lived in `android-app`, but the build only included `app`.

## Fix
- Change summary: Map :app to `android-app`, fix manifest/permissions/theme, and add minimal WebView logging.
- Files touched: `settings.gradle.kts`, `android-app/src/main/AndroidManifest.xml`, `android-app/src/main/java/com/netninja/MainActivity.kt`, `android-app/src/main/res/values/styles.xml`, `android-app/src/main/res/values/strings.xml`.

## Risk / Regression Notes
- Potential side effects: The placeholder `app/` module is no longer built by default.

## Verification
- Manual checks: Launch app, verify WebView loads `ninja_mobile.html` and stays open.
- Smoke tests: Not run (manual only).
