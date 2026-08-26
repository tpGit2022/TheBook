# TheBook

Personal Android application for record tracking, statistics, data export, and file encryption/decryption.

## Local build environment

The persistent Gradle user home on this development machine is:

- Windows: `E:\MySDK\GradleCache\.gradle`
- WSL: `/mnt/e/MySDK/GradleCache/.gradle`

When building from WSL, use:

```bash
GRADLE_USER_HOME=/mnt/e/MySDK/GradleCache/.gradle ./gradlew assembleDebug
```

Android Studio should use the same directory as its Gradle user home so dependencies remain available after a system reinstall.
