#!/usr/bin/env bash
# Runs the Android unit tests. See tests/android/build.gradle for why they live in their own Gradle
# project rather than in `android/`.
set -euo pipefail
cd "$(dirname "$0")/../tests/android"
exec ./gradlew testDebugUnitTest "$@"
