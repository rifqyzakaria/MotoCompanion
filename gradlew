#!/usr/bin/env sh
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "\`dirname \"$PRG\"\`" >/dev/null 2>&1 && pwd`

if [ -x "/home/antigravity/gradle/gradle-8.5/bin/gradle" ]; then
    exec "/home/antigravity/gradle/gradle-8.5/bin/gradle" "$@"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    exec "$JAVA_HOME/bin/java" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
else
    exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
fi
