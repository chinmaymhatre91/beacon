#!/bin/bash
set -x
FOLDER_NAMES=("linux.gtk.x86" "linux.gtk.x86_64" "macosx.cocoa.x86" "macosx.cocoa.x86_64" "win32.win32.x86" "win32.win32.x86_64")
ARCHIVE_NAMES=("linux_x86" "linux_x86_64" "osx_x86" "osx_x86_64" "win32_x86" "win32_x86_64")

if [ $# -ne 1 ]; then
  echo "Usage: $0 <VERSION>"
  echo "       $0 1.0.0"
  exit 65
fi

VERSION=$1

cd dist
for i in {0..5}; do
  cd ${FOLDER_NAMES[$i]}
  tar -czv -f ../beacon-${VERSION}-${ARCHIVE_NAMES[$i]}.tar.gz beacon
  cd ../
done
cd ../../
tar -czv -f beacon/dist/beacon-${VERSION}-source.tar.gz --exclude-vcs --exclude="beacon/dist" beacon
cd beacon

