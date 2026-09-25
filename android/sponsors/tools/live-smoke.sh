#!/usr/bin/env bash
# Environment keys are consumed only by a short-lived JVM, never a Gradle daemon.
# No .env file is loaded and no network probe runs without its explicit command.
set -euo pipefail
set +x
umask 077

task_script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CLEARLINE_REPOSITORY_ROOT=$(cd -- "$task_script_dir/../../.." && pwd)
task_toolchain_root=${CLEARLINE_TOOLCHAIN_ROOT:-/private/tmp/clearline-android-toolchain}
if [[ ! -f "$task_toolchain_root/env.sh" ]]; then
  printf '%s\n' 'Missing project toolchain. See android/docs/TOOLCHAIN.md.' >&2
  exit 1
fi
source "$task_toolchain_root/env.sh"
task_build_root=${CLEARLINE_SPONSOR_SMOKE_BUILD_ROOT:-$task_toolchain_root/smoke-builds}
mkdir -p "$task_build_root"
task_project_dir=$(mktemp -d "$task_build_root/sponsor-live.XXXXXX")
trap 'rm -rf -- "$task_project_dir"' EXIT
cp "$task_script_dir/live-smoke/settings.gradle.kts" "$task_project_dir/settings.gradle.kts"
cp "$task_script_dir/live-smoke/build.gradle.kts" "$task_project_dir/build.gradle.kts"

# Dependency artifacts must already be installed by the project build. The
# default config check cannot unexpectedly contact Maven or a sponsor service.
task_gradle_task=writeRuntimeClasspath
if [[ $# -eq 1 && $1 == self-test ]]; then task_gradle_task=test; fi
env -u NIMBLE_API_KEY -u RAWTREE_API_KEY -u RAWTREE_DATABASE \
  "$task_toolchain_root/gradle-8.11.1/bin/gradle" --offline --no-daemon \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --console=plain \
  -p "$task_project_dir" "$task_gradle_task"
if [[ "$task_gradle_task" == test ]]; then exit 0; fi

# Token values never appear in arguments, task inputs, source files or reports.
"$JAVA_HOME/bin/java" -cp "$(cat "$task_project_dir/build/runtime-classpath.txt")" \
  com.clearline.sponsors.SponsorLiveSmokeKt "$@"
