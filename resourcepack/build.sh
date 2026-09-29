#!/usr/bin/env bash
# Rebuilds keepersmp-sift.zip from resourcepack/src and prints its SHA-1.
# Run this after editing any file under resourcepack/src, then paste the
# printed hash into config.yml's resourcepack.sha1.
set -euo pipefail
cd "$(dirname "$0")"
rm -f keepersmp-sift.zip
(cd src && zip -r -X ../keepersmp-sift.zip . -x '.*')
echo "SHA-1:"
sha1sum keepersmp-sift.zip
