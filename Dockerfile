# Multi-stage build for the Breath-Ezy CDS gateway (FL-34 Track A).
#
# Stage 1 (build):   pinned OpenCDS 7.1.0-SNAPSHOT source -> deployable CDS-Hooks WAR.
#   TZ=America/Phoenix (permanent MST) neutralises the timezone-hardcoded R4 unit
#   test; a container otherwise defaults to UTC and that test reddens. See build.sh.
# Stage 2 (km):      our 9 FL-30 knowledge modules + the checksummed kb/ bundle -> a jar.
# Stage 3 (runtime): Tomcat 10 (Jakarta EE namespace, which the WAR targets) on JRE 17,
#   the WAR deployed at context /opencds, our KM jar on its classpath, and the Node
#   shim alongside. The CDS-Hooks front door is
#     GET  /opencds/r4/hooks/cds-services          (discovery — lists the 9 KMs)
#     POST /opencds/r4/hooks/cds-services/<km-id>  (evaluate)
#   and the shim's locked-JSON front door is
#     POST :8081/pharm-check
#
# Deploying this image does NOT open a patient-facing path. The breath-ezy cds-adapter slot
# stays EMPTY->HARD_FAIL until an endpoint is wired AND staging-validated (A4), receipts stay
# mode=mock until FL-50, and the client re-validates every response fail-closed regardless of
# what this image says.

# ---- Stage 1: build the OpenCDS WAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
ENV TZ=America/Phoenix
WORKDIR /gateway
# Copy the pin + build script first so a source-only change reuses the cloned layer.
COPY pinned-commits.env build.sh ./
RUN chmod +x build.sh && ./build.sh

# ---- Stage 2: build the FL-30 knowledge modules ----
# Reuses stage 1's ~/.m2, where build.sh installed the pinned 7.1.0-SNAPSHOT artifacts the
# KMs compile against. Nothing is downloaded: -o keeps this honest, so a KM can never quietly
# resolve a DIFFERENT OpenCDS than the WAR was built from.
FROM build AS km
WORKDIR /km
COPY km/pom.xml ./pom.xml
COPY km/src ./src
# The KB is packaged from the ONE committed copy at the repo root (km/pom.xml points at ../kb),
# so the jar cannot carry a bundle that drifted from the manifest the export wrote.
COPY kb /kb
RUN mvn -o -q -DskipTests package && ls -l target/*.jar

# Explode the WAR HERE, where a JDK exists. The runtime image is tomcat:10-jre17 — a JRE, so it
# has neither `jar` nor `unzip`, and adding a package to the runtime just to unpack an archive
# would be a dependency bought for one command.
RUN mkdir -p /war && cd /war  && jar xf /gateway/src/opencds-example/opencds-hooks-example-service/target/opencds-hooks-example-service.war

# ---- Stage 3: runtime ----
FROM tomcat:10-jre17
ENV TZ=America/Phoenix

# The example service is built (Maven-filtered) with ABSOLUTE build-time paths to its
# knowledge repository — knowledge-repository.path and config.security in
# dot-opencds/opencds-hooks.properties point at /gateway/src/.../target/classes/... which
# does not exist in this runtime stage (only the WAR is copied). Both directories ARE
# inside the WAR (WEB-INF/classes/{k-repo,dot-opencds}); beans.xml loads the properties
# with system-properties-mode="OVERRIDE", so we override both to the exploded WAR's
# classpath location (Tomcat unpacks opencds.war to webapps/opencds/ by default). Without
# this, the /opencds context fails to start (SIMPLE_FILE k-repo not found).
ENV CATALINA_OPTS="-Dknowledge-repository.path=/usr/local/tomcat/webapps/opencds/WEB-INF/classes/k-repo -Dconfig.security=/usr/local/tomcat/webapps/opencds/WEB-INF/classes/dot-opencds/sec.xml"

# Remove the default Tomcat apps; we serve only the OpenCDS context.
RUN rm -rf /usr/local/tomcat/webapps/* \
 && mkdir -p /usr/local/tomcat/webapps/opencds

# The EXPLODED context, unpacked in the km stage (which has a JDK). Deliberately not left to
# Tomcat: it only expands an archive when the target directory is ABSENT, and we must write INTO
# that directory (the k-repo below) — which would suppress the expansion and leave an empty
# context. Exploding here makes the layout explicit instead of dependent on boot-time ordering.
COPY --from=km /war/ /usr/local/tomcat/webapps/opencds/

# Our KMs + gson onto the WEB-INF classpath. OpenCDS's CdsHooksKnowledgeLoader does
# Class.forName(packageId) through the WEBAPP's classloader, so a KM jar in tomcat/lib would
# NOT be found — WEB-INF/lib is the only place this works.
COPY --from=km /km/target/breath-ezy-fl30-km-1.0.0-SNAPSHOT.jar /usr/local/tomcat/webapps/opencds/WEB-INF/lib/
# gson is `provided` in km/pom.xml because the WAR already ships it — assert that rather than
# assume it. If the WAR ever stops carrying gson, the KMs would fail with NoClassDefFoundError
# at request time (a runtime surprise, on the safety path); this turns that into a build failure.
RUN ls /usr/local/tomcat/webapps/opencds/WEB-INF/lib/ | grep -q '^gson-' \
    || (echo "!! the WAR does not ship gson — km/pom.xml marks it provided; either bundle it or fix the scope" >&2; exit 1)

# THE KNOWLEDGE MODULE REGISTRATIONS. Replace ONLY knowledgeModules.xml: the k-repo also holds
# executionEngines.xml ("CDS Hooks Adapter"), semanticSignifiers.xml ("cds-hooks-fhir-r4"),
# conceptDeterminationMethods/, plugins/ and supportingData/ — all of which our 9 registrations
# reference by id. Pointing knowledge-repository.path at a directory containing only our file
# would drop those and the context would not start.
COPY km/k-repo/knowledgeModules.xml /usr/local/tomcat/webapps/opencds/WEB-INF/classes/k-repo/knowledgeModules.xml

# The Node shim. The binary is copied from the official image rather than apt-installed: Debian's
# nodejs package lags, and the shim's fetch()/AbortController need a current Node. Both images are
# Debian bookworm, so the runtime libs line up.
COPY --from=node:20-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY shim /opt/fl30/shim
ENV SHIM_PORT=8081 \
    OPENCDS_BASE=http://localhost:8080/opencds

COPY entrypoint.sh /opt/fl30/entrypoint.sh
RUN chmod +x /opt/fl30/entrypoint.sh \
 && node --version

EXPOSE 8080 8081
CMD ["/opt/fl30/entrypoint.sh"]
