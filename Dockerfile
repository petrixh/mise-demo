FROM ghcr.io/jqlang/jq:latest AS jq-stage

FROM eclipse-temurin:21-jdk AS build
COPY --from=jq-stage /jq /usr/bin/jq
# Test that jq works after copying
RUN jq --version

ENV HOME=/app
RUN mkdir -p $HOME
WORKDIR $HOME
COPY . $HOME

# If you have a Vaadin Pro key, pass it as a secret with id "proKey":
#
#   $ docker build --secret id=proKey,src=$HOME/.vaadin/proKey .
#
# If you have a Vaadin Offline key, pass it as a secret with id "offlineKey":
#
#   $ docker build --secret id=offlineKey,src=$HOME/.vaadin/offlineKey .

# The app uses vaadin-chart (commercial, pulled in via the AI components), so a
# production build needs a license. Without one we fall back to Vaadin's
# watermarked build (-Dvaadin.commercialWithBanner) so a keyless
# `docker build .` still succeeds.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=proKey \
    --mount=type=secret,id=offlineKey \
    sh -c 'PRO_KEY=$(jq -r ".proKey // empty" /run/secrets/proKey 2>/dev/null || echo "") && \
    OFFLINE_KEY=$(cat /run/secrets/offlineKey 2>/dev/null || echo "") && \
    if [ -z "${PRO_KEY}" ] && [ -z "${OFFLINE_KEY}" ]; then BANNER=-Dvaadin.commercialWithBanner; fi && \
    ./mvnw clean package -DskipTests -Dvaadin.proKey=${PRO_KEY} -Dvaadin.offlineKey=${OFFLINE_KEY} ${BANNER}'

# --- Runtime base: everything except the JAR --------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Seed catalogs (recipes/stores/personas) are read from the filesystem at
# startup — application-prod.properties points at /app/demo/data. Mount your
# own directory over it to customize without rebuilding.
COPY demo/ demo/
# H2 file DB path under the prod profile; mount a volume here to persist
# plans, pantry, preferences, and conversation history across restarts.
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]

# --- Release target: JAR prebuilt on the host (used by release.yml) ---------
# The fat JAR is architecture-independent, so the release workflow builds it
# once natively and multi-archs only this stage — no Vaadin build under QEMU.
FROM runtime AS release
COPY target/*.jar /app/app.jar

# --- Default target: full in-container build (`docker build .` just works) --
FROM runtime AS standalone
COPY --from=build /app/target/*.jar /app/app.jar
