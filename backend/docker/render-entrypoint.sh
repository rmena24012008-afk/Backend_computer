#!/bin/sh
set -eu

PORT="${PORT:-10000}"
SERVER_XML="/usr/local/tomcat/conf/server.xml"

sed -i "s/port=\"8080\" protocol=\"HTTP\\/1.1\"/port=\"${PORT}\" protocol=\"HTTP\\/1.1\"/" "${SERVER_XML}"

exec catalina.sh run
