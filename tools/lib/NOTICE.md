# Test dependency

`json-20240303.jar` contains the core classes compiled from JSON-java tag `20240303`:
https://github.com/stleary/JSON-java/tree/20240303/src/main/java/org/json

Upstream LICENSE: Public Domain.
https://github.com/stleary/JSON-java/blob/20240303/LICENSE

This jar is only used by `tools/check-core.sh`. It is not included in the Android
application. The APK uses Android's built-in `org.json` implementation.

# Gradle Wrapper

The official wrapper jar in `gradle/wrapper/gradle-wrapper.jar` comes from Gradle
tag `v8.10.2`, licensed under Apache License 2.0:
https://github.com/gradle/gradle/blob/v8.10.2/LICENSE
