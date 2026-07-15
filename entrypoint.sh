#!/usr/bin/bash
# Single-container entrypoint: Tomcat (OpenCDS + the 9 FL-30 KMs) + the Node shim.
#
# The operator ruled a Node sidecar in ONE container (2026-07-14). Two processes, one PID namespace,
# and the container must die if EITHER dies — a half-alive gateway is the worst of both worlds:
#
#   - Tomcat dead, shim alive  → the shim answers every check NOT_RUN and km_set "unknown", so the
#     CLIENT blocks. Safe, but the container would look healthy while answering nothing. An operator
#     needs to see it fall over, not discover it in a log.
#   - Shim dead, Tomcat alive  → the client cannot reach the gateway at all → transport failure →
#     BLOCKED_NO_PROOF. Also safe, also invisible.
#
# Both failure modes are FAIL-SAFE by construction (the client blocks either way — nothing unsafe can
# emerge from a broken gateway). This script is not about safety; it is about not hiding a fault.
set -eu

echo "fl30-gateway: starting Tomcat…"
catalina.sh run &
TOMCAT_PID=$!

echo "fl30-gateway: starting the shim…"
node /opt/fl30/shim/server.mjs &
SHIM_PID=$!

# Whichever exits first takes the container with it. `wait -n` is a BASH builtin — the image's
# /bin/sh is dash, which rejects it, and the container exited 2 before Tomcat ever started. Hence
# the explicit bash shebang rather than a portable-looking /bin/sh that is not portable here.
wait -n "${TOMCAT_PID}" "${SHIM_PID}"
EXIT=$?
echo "fl30-gateway: a process exited (${EXIT}) — shutting down so the fault is visible"
kill "${TOMCAT_PID}" "${SHIM_PID}" 2>/dev/null || true
exit "${EXIT}"
