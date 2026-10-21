#!/bin/bash
# Prints the Instrumented workflow's matrix as JSON: every module with androidTests on main, only
# the ones a branch touched (plus the app, which depends on every feature) elsewhere.
# Usage: instrumented-matrix.sh <ref> [base]   ref like refs/heads/main; base defaults to origin/main
set -euo pipefail
ref=$1
base=${2:-origin/main}

all='app core-engine core-data feature-info feature-library feature-player feature-subtitles feature-audio'

modules() {
  if [ "$ref" = refs/heads/main ]; then echo "$all"; return; fi
  local changed
  changed=$(git diff --name-only "$(git merge-base HEAD "$base")" HEAD)
  local out=""
  while read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
      # Shared code and build setup reach every module.
      core/*|build-logic/*|gradle/*|*.gradle.kts|gradle.properties|.github/workflows/instrumented.yml|.github/scripts/*)
        echo "$all"; return ;;
      feature/info/*) out="$out feature-info app" ;;
      feature/library/*) out="$out feature-library app" ;;
      feature/player/*) out="$out feature-player app" ;;
      feature/subtitles/*) out="$out feature-subtitles app" ;;
      feature/audio/*) out="$out feature-audio app" ;;
      app/*|feature/*|bench/*|baselineprofile/*) out="$out app" ;;
    esac
  done <<< "$changed"
  echo "$out"
}

json="["
sep=""
for m in $(modules | tr ' ' '\n' | sort -u); do
  case "$m" in
    app) build=':app:assembleDebug :app:assembleDebugAndroidTest'; task=':app:connectedDebugAndroidTest' ;;
    *) p=":${m%%-*}:${m#*-}"; build="$p:assembleDebugAndroidTest"; task="$p:connectedDebugAndroidTest" ;;
  esac
  # Audio tests need the emulator's audio device.
  audio=false; [ "$m" = feature-audio ] && audio=true
  json="$json$sep{\"module\":\"$m\",\"build\":\"$build\",\"task\":\"$task\",\"audio\":$audio}"
  sep=","
done
echo "$json]"
