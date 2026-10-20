#!/usr/bin/env bash
# NF-8, US-72.2, US-72.3 source audit: no sign-in screens, no network code outside libmpv, and the
# full list of libraries the app pulls in. Run from the app repo root; prints a report.
set -uo pipefail
echo "# Source audit at $(git log --oneline -1 | cut -c1-7), $(date -u +%F)"
echo "## login, sign in, account in .kt and .xml"
git grep -n -i -E "login|sign.?in|account" -- '*.kt' '*.xml' | grep -v -i accounting || echo "(none)"
echo "## network code outside libmpv (HttpURLConnection, OkHttp, Retrofit, java.net.URL, Socket)"
git grep -n -E "HttpURLConnection|OkHttp|okhttp|Retrofit|java\.net\.URL\b|Socket\(" -- '*.kt' '*.kts' || echo "(none)"
echo "## every library in gradle/libs.versions.toml"
grep "module = " gradle/libs.versions.toml | sed -E 's/.*module = "([^"]*)".*/\1/' | sort -u
