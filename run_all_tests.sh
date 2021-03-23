#!/usr/bin/env bash
export SBT_OPTS="-XX:+CMSClassUnloadingEnabled -XX:MaxMetaspaceSize=1G"
sbt clean compile coverage test it:test coverageReport
python dependencyReport.py third-party-application
