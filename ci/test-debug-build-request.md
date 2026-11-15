# Debug-only CI verification

Temporary build verification for the `test` branch at `85e097fcdd5b666fcf95bee61147534d0ae3eec6`.

This documentation-only addition does not alter application sources or Gradle configuration. Build command from `.github/workflows/debug.yml`: `./gradlew :app:testDebugUnitTest :app:assembleDebug --build-cache --no-daemon --stacktrace`.
