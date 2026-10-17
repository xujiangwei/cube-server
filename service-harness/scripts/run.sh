#!/bin/sh
set -eu
# All three paths must be absolute; the stop marker belongs to this container only.
cd -- "$1"
exec "${HARNESS_JAVA:-java}" "-Dharness.config=$2" -cp 'bin/cell.jar:libs/*:cellets/cube-service-harness-3.0.jar' cube.service.harness.HarnessCell cell.xml "$3"
