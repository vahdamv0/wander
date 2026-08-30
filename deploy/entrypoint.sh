#!/bin/sh
# The image is the only thing a deployment has to fetch, so it also carries the
# files a server needs beside it: the compose file, the Caddyfile, the backup
# script and a commented .env to copy. `bundle` writes them to stdout as a tar.
#
#   docker run --rm <image> bundle | tar x
#
# The point is that the compose file and the image can never disagree. A second
# copy kept in a deploy repository drifts the first time somebody edits one and
# not the other, and the symptom of that is a stack that starts and is subtly
# wrong. Here they are built together and travel together.
set -e

if [ "$1" = "bundle" ]; then
	exec tar -cf - -C /app/deploy .
fi

# Anything else is the application. $JAVA_OPTS is unquoted on purpose: it is a
# list of arguments, not one.
exec java $JAVA_OPTS -jar /app/wander.jar "$@"
