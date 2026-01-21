#!/usr/bin/env bash
set -euo pipefail
# Simple helper to run the server locally with the Gradle wrapper
# Usage: ./scripts/dev-run-server.sh
./gradlew :server:run