#!/bin/bash

sbt "~run -Dlogger.application=INFO -Drun.mode=Dev -Dhttp.port=9607 -Dapplication.router=testOnlyDoNotUseInAppConf.Routes $*"