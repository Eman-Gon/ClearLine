#!/bin/sh
set -eu
AUDIO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PIN=a8d002cfd879315632a579e73f0148d06959de36
DEST="$AUDIO_ROOT/.native/whisper.cpp"
if [ ! -d "$DEST/.git" ]; then
  mkdir -p "$AUDIO_ROOT/.native"
  git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp.git "$DEST"
fi
ACTUAL=$(git -C "$DEST" rev-parse HEAD)
if [ "$ACTUAL" != "$PIN" ]; then
  printf '%s\n' 'Unexpected whisper.cpp revision; refusing build source.' >&2
  exit 1
fi
if [ -n "$(git -C "$DEST" status --porcelain)" ]; then
  printf '%s\n' 'Modified whisper.cpp source; refusing untracked native changes.' >&2
  exit 1
fi
printf '%s\n' "Verified whisper.cpp $PIN"
