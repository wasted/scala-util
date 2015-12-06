#!/bin/bash

root=$(
	cd $(dirname $(readlink $0 || echo $0))/..
	pwd
)

sbtver=0.13.9
sbtjar=sbt/bin/sbt-launch.jar
sbtsum=767d963ed266459aa8bf32184599786d

mkdir -p target

function download {
	echo "downloading sbt $sbtver" 1>&2
	curl -L -o target/sbt-${sbtver}.tgz "https://dl.bintray.com/sbt/native-packages/sbt/${sbtver}/sbt-${sbtver}.tgz"
	tar -zxpf target/sbt-${sbtver}.tgz -C target/
}

function sbtjar_md5 {
	openssl md5 < target/${sbtjar} | cut -f2 -d'=' | awk '{print $1}'
}

if [ ! -f "target/${sbtjar}" ]; then
	download
fi

test -f "target/${sbtjar}" || exit 1

jarmd5=$(sbtjar_md5)
if [ "${jarmd5}" != "${sbtsum}" ]; then
	echo "Bad MD5 checksum on ${sbtjar}!" 1>&2
	download

	jarmd5=$(sbtjar_md5)
	if [ "${jarmd5}" != "${sbtsum}" ]; then
		echo "Bad MD5 checksum *AGAIN*!" 1>&2
		exit 1
	fi
fi

test -f ~/.sbtconfig && . ~/.sbtconfig

if [ -f /usr/jrebel/jrebel.jar ]; then
	JREBEL="-noverify -javaagent:/usr/jrebel/jrebel.jar -Drebel.lift_plugin=true"
fi

java -ea -server $SBT_OPTS $JAVA_OPTS $JREBEL		\
	-XX:+AggressiveOpts             		\
	-XX:+OptimizeStringConcat			\
	-XX:+UseConcMarkSweepGC               		\
	-Xms128M					\
	-Xmx2G						\
	-Djava.net.preferIPv4Stack=true                 \
	-jar target/$sbtjar "$@"

