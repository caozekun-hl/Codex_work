#!/bin/sh
# Verify production code without Android SDK or network. Requires JDK 17+.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT HUP INT TERM
JSON_JAR=${1:-"$PROJECT_DIR/tools/lib/json-20240303.jar"}
if [ -n "${JAVA_HOME:-}" ]; then JAVA_CMD="$JAVA_HOME/bin/java"; else JAVA_CMD=java; fi
"$JAVA_CMD" com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$CHECK_DIR" \
    "$PROJECT_DIR/app/src/main/java/cn/juyan/ai/AppState.java" \
    "$PROJECT_DIR/app/src/main/java/cn/juyan/ai/AiProtocol.java" \
    "$PROJECT_DIR/app/src/main/java/cn/juyan/ai/AiClient.java" \
    "$PROJECT_DIR/app/src/main/java/cn/juyan/ai/ConfigImport.java" \
    "$PROJECT_DIR/app/src/test/java/cn/juyan/ai/CoreChecks.java"
"$JAVA_CMD" -cp "$CHECK_DIR:$JSON_JAR" cn.juyan.ai.CoreChecks
