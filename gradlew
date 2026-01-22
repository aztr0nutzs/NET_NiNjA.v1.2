#!/usr/bin/env sh
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVA" -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
