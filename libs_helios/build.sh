#!/bin/bash

set -ex

# You must have exported ECLIPSE_HOME for this to work, ie:
# export ECLIPSE_HOME=/home/username/eclipse
BASEDIR=`pwd`

#rm -rfd temp
#mkdir -p temp/plugins
#mv plugins/* temp/plugins/
java -jar $ECLIPSE_HOME/plugins/org.eclipse.equinox.launcher_*.jar \
   -application org.eclipse.ant.core.antRunner \
   -f mirror.xml
#rm -rfd temp
