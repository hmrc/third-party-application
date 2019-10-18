#!/usr/bin/env bash
mongo third-party-application-test --eval "db.dropDatabase()"
sbt clean compile test it:test
python dependencyReport.py third-party-application
