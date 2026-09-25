#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: JAVA_HOME=/path/to/jdk run-host.sh /path/to/pinned-archive.tar.gz /tmp/build-directory" >&2
  exit 2
fi
inference_dir="$(cd "$(dirname "$0")/../.." && pwd)"
archive="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
build_dir="$2"
cmake -S "$inference_dir/src/main/cpp" -B "$build_dir" \
  -DCLEARLINE_LLAMA_ARCHIVE="$archive" -DCLEARLINE_NATIVE_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --target clearline_liquid clearline_cancel_test --parallel 4
ctest --test-dir "$build_dir" --output-on-failure
"${JAVA_HOME:?Set JAVA_HOME}/bin/javac" -d "$build_dir/jni-smoke" "$inference_dir/native/tests/NativeSmoke.java"
extension=so
if [[ "$(uname)" == Darwin ]]; then extension=dylib; fi
"$JAVA_HOME/bin/java" -Xcheck:jni -cp "$build_dir/jni-smoke" com.clearline.inference.NativeSmoke \
  "$build_dir/libclearline_liquid.$extension"
