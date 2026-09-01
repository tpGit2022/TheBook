# Project Instructions

## Permanent build constraint

- Never run any Gradle build command in WSL, including `gradle`, `gradlew`, and `./gradlew`.
- Gradle builds may be run directly by Codex when the current environment is native Windows PowerShell 7 (pwsh).
- This WSL-only restriction is permanent and applies to all future sessions and agents working in this repository.
