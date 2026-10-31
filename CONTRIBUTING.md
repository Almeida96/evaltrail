# Contributing

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew test installDist distZip
```

On Windows, run `gradlew.bat`. Compiler warnings fail the build. Dependencies are pinned in `gradle.lockfile`; when changing them, regenerate locks with `./gradlew dependencies --write-locks` and review the diff.

For a bug report, include the command, Java/OS versions, expected result, and a minimal sanitized suite/observation. Do not attach access tokens or real customer traces. The CLI intentionally omits payloads from error messages, so a small reproducer is more useful than a screenshot of a generic input error.

For a new check, define its input schema and matching semantics before implementing it. Add coverage for a passing case, a meaningful failure, invalid configuration, and missing observation data. Update the check reference and consider whether the grader version must change. Tests should verify behavior through the grader, HTTP runner, or CLI, rather than duplicating private implementation details.

For report changes, preserve the distinction between assertion failures and unavailable observations. Changed or missing cases must not be counted as recoveries. Changes to persisted formats need an explicit schema compatibility decision.

Keep changes focused and describe the user-visible behavior in the commit or pull request. CI runs tests and the local demo on Linux and Windows. No provider key is required. See [architecture](docs/architecture.md) for module boundaries and limits.
