#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p libs
fetch() { if [ ! -f "libs/$1" ]; then curl -fL --retry 3 --connect-timeout 20 --max-time 600 -o "libs/$1.download" "https://repo.maven.apache.org/maven2/$2"; mv "libs/$1.download" "libs/$1"; fi; }
fetch jsch-0.2.26.jar com/github/mwiede/jsch/0.2.26/jsch-0.2.26.jar
fetch bcprov-jdk18on-1.77.jar org/bouncycastle/bcprov-jdk18on/1.77/bcprov-jdk18on-1.77.jar
fetch json-20240303.jar org/json/json/20240303/json-20240303.jar
shasum -a 256 -c dependencies.sha256

# Restore the downloadable Markdown runtime omitted from source handoffs.
MARKDOWN=app/src/main/assets/markdown-it.min.js
if [ ! -f "$MARKDOWN" ]; then
  curl -fL --retry 3 --connect-timeout 20 --max-time 600 -o "$MARKDOWN.download" https://cdn.jsdelivr.net/npm/markdown-it@14.1.0/dist/markdown-it.min.js
  mv "$MARKDOWN.download" "$MARKDOWN"
fi
printf "38c70a1e7ca91ab40e2d9e6e60129851a717ed1c7d4acbbdd41bf9503791cf68  %s\n" "$MARKDOWN" | shasum -a 256 -c -
