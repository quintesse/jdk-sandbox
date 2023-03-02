#!/bin/bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
JAVA_HOME=$(dirname $(dirname "$SCRIPT_DIR"))/build/linux-x86_64-server-release/images/jdk
PATH=$JAVA_HOME/bin:$PATH

echo $JAVA_HOME
( cd $SCRIPT_DIR && (
    javac -d out module-info.java
    javac -d out --module-path out helloworld/HelloWorld.java
))
