#!/usr/bin/env bash
# Runs inside the emulator job: a real ejabberd, the demo, and its end-to-end test.
set -euo pipefail
docker run -d --name ejabberd -p 5222:5222 ghcr.io/processone/ejabberd:latest
for i in $(seq 90); do docker exec ejabberd ejabberdctl status >/dev/null 2>&1 && break; sleep 2; done
docker exec ejabberd ejabberdctl register alice localhost secret
docker exec ejabberd ejabberdctl register bob localhost secret

adb install -r out/outputs/apk/debug/app-debug.apk
adb install -r out/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
set +e
adb shell am instrument -w -r \
  -e xmppHost 10.0.2.2 -e xmppDomain localhost -e xmppPassword secret \
  io.github.usmandurrany.tether.demo.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
adb exec-out screencap -p > e2e-end.png
adb logcat -d > e2e-logcat.txt
docker logs ejabberd > ejabberd.txt 2>&1
set -e
grep -q "OK (1 test)" instrument.txt
