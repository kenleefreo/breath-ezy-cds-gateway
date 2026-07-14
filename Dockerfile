# Two-stage build for the Breath-Ezy CDS gateway (FL-34 Track A).
#
# Stage 1 (build): pinned OpenCDS 7.1.0-SNAPSHOT source -> deployable CDS-Hooks WAR.
#   TZ=America/Phoenix (permanent MST) neutralises the timezone-hardcoded R4 unit
#   test; a container otherwise defaults to UTC and that test reddens. See build.sh.
# Stage 2 (runtime): Tomcat 10 (Jakarta EE namespace, which the WAR targets) on JRE 17,
#   with the WAR deployed at context /opencds. The CDS-Hooks front door is then
#   GET /opencds/r4/hooks/cds-services  (discovery) and
#   POST /opencds/r4/hooks/cds-services/<km-id>  (evaluate).
#
# This image is the DEPLOYED OpenCDS execution engine only. It carries NO Breath-Ezy
# knowledge yet: the FL-30 KB->KM package (Phase B) and the translation shim (Phase C)
# are added in later phases. Until an endpoint is wired AND staging-validated (A4),
# the breath-ezy cds-adapter slot stays EMPTY->HARD_FAIL — deploying this image does
# not by itself open any patient-facing path.

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
ENV TZ=America/Phoenix
WORKDIR /gateway
# Copy the pin + build script first so a source-only change reuses the cloned layer.
COPY pinned-commits.env build.sh ./
RUN chmod +x build.sh && ./build.sh

# ---- Stage 2: runtime ----
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
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build \
  /gateway/src/opencds-example/opencds-hooks-example-service/target/opencds-hooks-example-service.war \
  /usr/local/tomcat/webapps/opencds.war
EXPOSE 8080
CMD ["catalina.sh", "run"]
