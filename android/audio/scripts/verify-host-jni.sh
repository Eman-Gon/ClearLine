#!/bin/sh
set -eu
: "${JAVA_HOME:?Set JAVA_HOME to JDK17}"
AUDIO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CHECK_ROOT=${CLEARLINE_ASR_HOST_BUILD:-/tmp/clearline-whisper-native-build}
cmake -S "$AUDIO_ROOT/src/main/cpp" -B "$CHECK_ROOT" -DCMAKE_BUILD_TYPE=Release
cmake --build "$CHECK_ROOT" -j 4
mkdir -p "$CHECK_ROOT/jni-smoke"
"$JAVA_HOME/bin/javac" -d "$CHECK_ROOT/jni-smoke" "$AUDIO_ROOT/src/test/native/NativeWhisperBridge.java"
"$JAVA_HOME/bin/java" -Djava.library.path="$CHECK_ROOT" -cp "$CHECK_ROOT/jni-smoke" com.clearline.audio.NativeWhisperBridge
