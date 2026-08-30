# TheBook

Personal Android application for record tracking, statistics, data import/export, and file encryption/decryption.

## Local build environment

Gradle builds must never be run from WSL for this project. Run builds from
Windows PowerShell instead, for example:

```powershell
$env:GRADLE_USER_HOME = 'E:\MySDK\GradleCache\.gradle'
.\gradlew.bat assembleDebug
```

Android Studio should use `E:\MySDK\GradleCache\.gradle` as its Gradle user
home so dependencies remain available after a system reinstall.
