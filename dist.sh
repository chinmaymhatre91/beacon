#!/bin/bash
set -x
TMP=`mktemp -d`
CWD=`pwd`

ARCHIVE_DIR="net.beaconcontroller.product/target/products"
ARCHIVE_NAMES=("beacon-all-linux.gtk.x86_64.tar.gz" "beacon-all-linux.gtk.x86.tar.gz" "beacon-all-macosx.cocoa.x86_64.tar.gz" "beacon-all-win32.win32.x86_64.zip" "beacon-all-win32.win32.x86.zip")
ARCHIVE_LEARNINGSWITCH="beacon-learningswitch-linux.gtk.x86.tar.gz"
NEW_ARCHIVE_DIR="dist/"
NEW_ARCHIVE_NAMES=("beacon-version-linux_x86_64.tar.gz" "beacon-version-linux_x86.tar.gz" "beacon-version-osx_x86_64.tar.gz" "beacon-version-win_x86_64.zip" "beacon-version-win_x86.zip")
ADDITIONAL_FILES=("LICENSE.txt" "LICENSE_EXCEPTION.txt" "beacon.properties")

if [ $# -ne 1 ]; then
  echo "Usage: $0 <VERSION>"
  echo "       $0 1.0.0"
  exit 65
fi

VERSION=$1

# Delete any existing archives
for ARCHIVE_NAME in "${NEW_ARCHIVE_NAMES[@]}"; do
    rm -rf ${CWD}/${NEW_ARCHIVE_DIR}${ARCHIVE_NAME//version/$VERSION}
done
rm -rf ${CWD}/dist/beacon-${VERSION}-source.tar.gz

# Repack each archive with additional files listed above
for i in `seq 1 ${#ARCHIVE_NAMES[@]}`; do
    ARCHIVE_NAME=${ARCHIVE_NAMES[$i]}
    # Extract existing archive
    if [[ $ARCHIVE_NAME =~ ^.*gz$ ]]; then
        tar xzvf ${ARCHIVE_DIR}/${ARCHIVE_NAME} -C ${TMP}
    fi
    if [[ $ARCHIVE_NAME =~ ^.*zip$ ]]; then
        unzip ${ARCHIVE_DIR}/${ARCHIVE_NAME} -d ${TMP}
    fi
   
    # Add missing files
    RELEASE_DIR=`\ls ${TMP}`
    for FILE in "${ADDITIONAL_FILES[@]}"; do
        cp -a ${CWD}/${FILE} ${TMP}/${RELEASE_DIR}/
    done
    # Add learningswitch only configuration
    # runnable via ./beacon -configuration ./configurationSwitch
    mv ${TMP}/${RELEASE_DIR}/configuration ${TMP}/${RELEASE_DIR}/configurationT
    tar xzvf ${CWD}/${ARCHIVE_DIR}/${ARCHIVE_LEARNINGSWITCH} -C ${TMP} ${RELEASE_DIR}/configuration
    mv ${TMP}/${RELEASE_DIR}/configuration ${TMP}/${RELEASE_DIR}/configurationSwitch
    mv ${TMP}/${RELEASE_DIR}/configurationT ${TMP}/${RELEASE_DIR}/configuration
    
    # Repack archive
    if [[ $ARCHIVE_NAME =~ ^.*gz$ ]]; then
        tar czvf ${NEW_ARCHIVE_DIR}/${NEW_ARCHIVE_NAMES[$i]//version/$VERSION} -C ${TMP} ${RELEASE_DIR}
    fi
    if [[ $ARCHIVE_NAME =~ ^.*zip$ ]]; then
        pushd .
        cd ${TMP}
        zip -r ${CWD}/${NEW_ARCHIVE_DIR}/${NEW_ARCHIVE_NAMES[$i]//version/$VERSION} *
        popd
    fi
        
    rm -rf ${TMP}/*
done
mkdir -p ${TMP}/beacon-${VERSION}
cp -ar * ${TMP}/beacon-${VERSION}
tar -czv -f ${CWD}/dist/beacon-${VERSION}-source.tar.gz --exclude-vcs --exclude="beacon-${VERSION}/dist" --exclude="**/target" --exclude="**/bin" -C ${TMP} beacon-${VERSION}
rm -rfd ${TMP}
#cd ../../
#cd beacon

