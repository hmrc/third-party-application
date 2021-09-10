#!/bin/bash

sbt "~run -Drun.mode=Dev -Dhttp.port=9607 -Dmicroservice.services.api-platform-events.enabled=false $*"
