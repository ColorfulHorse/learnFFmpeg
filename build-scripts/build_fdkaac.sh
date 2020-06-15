#!/bin/bash

ARCH=$1

source config.sh $ARCH
LIBS_DIR=$(cd `dirname $0`; pwd)/libs/libfdk-aac

cd fdk-aac-2.0.1


PREFIX=$LIBS_DIR/$AOSP_ABI
echo "PREFIX="$PREFIX

export CC="$CC"
export CXX="$CXX"
export CFLAGS="$FF_CFLAGS"
export CXXFLAGS="$FF_EXTRA_CFLAGS"
export LDFLAGS="-lm"
export AR="${CROSS_PREFIX}ar"
export LD="${CROSS_PREFIX}ld"
export AS="${CROSS_PREFIX}as"


./configure \
--prefix=$PREFIX \
--target=android \
--with-sysroot=$SYS_ROOT \
--enable-static \
--disable-shared \
--host=$HOST 


make clean
make -j2
make install

cd ..
