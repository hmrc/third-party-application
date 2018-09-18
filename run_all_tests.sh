#!/usr/bin/env bash
mongo third-party-application-test --eval "db.dropDatabase()"
sbt clean compile coverage test it:test coverageReport
python dependencyReport.py third-party-application
