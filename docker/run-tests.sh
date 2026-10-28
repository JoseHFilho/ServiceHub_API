#!/bin/sh
# Runs only inside the disposable JDK build stage, never in the API runtime.
./mvnw -B -ntp "$@" clean verify
result=$?
mkdir -p /results
if [ -d target/surefire-reports ]; then cp -R target/surefire-reports /results/; fi
if [ -d target/failsafe-reports ]; then cp -R target/failsafe-reports /results/; fi
if [ -d target/site/jacoco ]; then cp -R target/site/jacoco /results/; fi
exit "$result"
