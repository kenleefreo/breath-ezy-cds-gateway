#!/usr/bin/env bash
#
# build.sh — pinned, reproducible OpenCDS build for the Breath-Ezy CDS gateway.
#
# Clones the 7 OpenCDS repositories at the EXACT commit hashes in pinned-commits.env,
# then builds them with Maven. Produces the deployable CDS-Hooks service WAR:
#   opencds/opencds-example/opencds-hooks-example-service/target/opencds-hooks-example-service.war
#
# WHY THE TZ PIN: opencds-hooks-model-r4 contains a unit test (CdsRequestSpec) that
# asserts a date rendered in the OpenCDS developers' local timezone (US Mountain).
# It fails on any machine not in MST. TZ=America/Phoenix is permanently MST (no DST),
# so the build is deterministic regardless of the host clock. See README.md.
#
# Requirements: git, JDK 17, Maven 3.9+. No network access beyond Bitbucket + Maven Central.
# Usage:  ./build.sh            (clones into ./src/, builds)
#         SKIP_TESTS=1 ./build.sh   (build without running tests — image path)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${ROOT}/src"
# shellcheck source=pinned-commits.env
source "${ROOT}/pinned-commits.env"

# repo -> pinned SHA (associative order preserved for readable logs)
declare -a REPOS=(
  "opencds:${OPENCDS_MASTER}"
  "opencds-config-store-je:${OPENCDS_CONFIG_STORE_JE}"
  "opencds-example:${OPENCDS_EXAMPLE}"
  "opencds-hooks:${OPENCDS_HOOKS}"
  "opencds-plugins:${OPENCDS_PLUGINS}"
  "opencds-war:${OPENCDS_WAR}"
  "opencds-webapp:${OPENCDS_WEBAPP}"
)

mkdir -p "${SRC}"
cd "${SRC}"

for entry in "${REPOS[@]}"; do
  repo="${entry%%:*}"
  sha="${entry##*:}"
  url="https://bitbucket.org/opencds/${repo}.git"
  if [ -d "${repo}/.git" ]; then
    echo ">> ${repo}: fetching, pinning to ${sha}"
    git -C "${repo}" fetch --quiet origin
  else
    echo ">> ${repo}: cloning"
    git clone --quiet "${url}" "${repo}"
  fi
  # Detach at the exact pinned commit — never a moving branch tip.
  git -C "${repo}" -c advice.detachedHead=false checkout --quiet "${sha}"
  actual="$(git -C "${repo}" rev-parse HEAD)"
  if [ "${actual}" != "${sha}" ]; then
    echo "!! ${repo}: expected ${sha}, got ${actual}" >&2
    exit 1
  fi
done

echo ">> building (opencds-build aggregator via opencds/pom.xml is not used; the"
echo "   example service module pulls the reactor it needs). TZ pinned to MST."

# The example project is the deployable target; building it with the reactor from
# opencds resolves all 7 pinned repos through the local Maven repository. We build
# opencds first (installs BOM/parent/artifacts), then the example service.
MVN_FLAGS="-q -DskipTests"
[ "${SKIP_TESTS:-0}" = "1" ] || MVN_FLAGS="-q"

# Order matters: opencds -> config-store-je -> hooks -> plugins -> webapp -> war -> example.
for repo in opencds opencds-config-store-je opencds-hooks opencds-plugins opencds-webapp opencds-war opencds-example; do
  echo ">> mvn install: ${repo}"
  ( cd "${repo}" && TZ=America/Phoenix mvn ${MVN_FLAGS} clean install )
done

WAR="${SRC}/opencds-example/opencds-hooks-example-service/target/opencds-hooks-example-service.war"
if [ ! -f "${WAR}" ]; then
  echo "!! build finished but WAR not found at ${WAR}" >&2
  exit 1
fi
echo ">> OK — WAR at ${WAR}"
