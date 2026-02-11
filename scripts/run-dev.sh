#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Building server shadow JAR..."
./gradlew :server:shadowJar

echo "==> Starting NET NiNjA server (dev mode)..."
java -jar server/build/libs/server-all.jar