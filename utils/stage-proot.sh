#!/usr/bin/env bash
# Copies a proot build into the app's native library folder under the names the app looks for.
#
#   utils/stage-proot.sh <abi> <path-to-proot> <path-to-proot-loader>
#   utils/stage-proot.sh arm64-v8a build/root/bin/proot build/root/libexec/proot/loader
#
# <abi> is arm64-v8a, armeabi-v7a or x86_64. See docs/terminal.md for where to get proot.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

abi="$1"; proot="$2"; loader="$3"
case "$abi" in arm64-v8a|armeabi-v7a|x86_64) ;; *) echo "Unknown ABI: $abi" >&2; exit 2 ;; esac
for f in "$proot" "$loader"; do
  [ -f "$f" ] || { echo "Not a file: $f" >&2; exit 2; }
done

dest="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/$abi"
mkdir -p "$dest"
cp "$proot" "$dest/libproot.so"
cp "$loader" "$dest/libproot-loader.so"
chmod 755 "$dest/libproot.so" "$dest/libproot-loader.so"
echo "Staged into $dest:"
sha256sum "$dest/libproot.so" "$dest/libproot-loader.so"
