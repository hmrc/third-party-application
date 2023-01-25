#!/usr/bin/env bash
mongo third-party-application-test --eval "db.dropDatabase()"
export SBT_OPTS="-XX:+CMSClassUnloadingEnabled -XX:MaxMetaspaceSize=1G"
sbt clean compile test it:test
