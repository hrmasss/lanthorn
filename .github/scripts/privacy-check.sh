#!/bin/bash
# P2.1, NF-8: fails when the release app asks for a permission not listed in
# .github/privacy/permissions.txt, or ships a library from .github/privacy/trackers.txt.
# Usage: privacy-check.sh <release apk> <release-dependencies.txt>   (prints a markdown report)
set -uo pipefail
apk=$1
deps=$2
dir=$(dirname "$0")/../privacy
aapt2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 | sort -V | tail -1)
fail=0

echo "## Privacy check (NF-8)"
echo "Release APK: \`$(basename "$apk")\`"
echo
echo "### Permissions"
allowed=$(grep -v '^#' "$dir/permissions.txt" | awk 'NF {print $1}')
"$aapt2" dump permissions "$apk" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u > /tmp/privacy-perms.txt
while read -r p; do
  if grep -qxF "$p" <<< "$allowed"; then echo "- $p"; else echo "- **NOT ALLOWED: $p** (add it to .github/privacy/permissions.txt with a reason, or remove it)"; fail=1; fi
done < /tmp/privacy-perms.txt

echo
echo "### Tracking, ads, telemetry libraries"
hits=0
while read -r t; do
  [[ -z "$t" || "$t" == \#* ]] && continue
  if m=$(grep -F "$t" "$deps" | head -3); [ -n "$m" ]; then
    echo "- **FOUND $t:**"; echo '```'; echo "$m"; echo '```'; hits=1; fail=1
  fi
done < "$dir/trackers.txt"
[ $hits = 0 ] && echo "None of the $(grep -cv '^#' "$dir/trackers.txt") listed groups in the release dependency tree."

echo
echo "### Network code outside libmpv"
net=$(git grep -n -E "HttpURLConnection|OkHttp|okhttp|Retrofit|java\.net\.URL\b|Socket\(|URLConnection" -- '*.kt' ':!**/src/test/**' ':!**/src/androidTest/**' || true)
if [ -n "$net" ]; then echo '```'; echo "$net"; echo '```'; else echo "None."; fi
exit $fail
