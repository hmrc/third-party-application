#!/usr/bin/env bash
export SBT_OPTS="-XX:MaxMetaspaceSize=1G --add-opens=java.base/java.net=ALL-UNNAMED"
sbt pre-commit

