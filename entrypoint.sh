#!/bin/sh
set -e
exec java $JAVA_OPTS -jar /app/app.jar --config /config.yml