# syntax=docker/dockerfile:1
# Radiance (ADR 0018): the SPA and backend in one image, built in CI and only pulled on the server.
#
# Multi-arch safe: the web and jar stages run on the builder's own platform (the jar is architecture-
# neutral; sqlite-jdbc carries native libraries for each one), and the runtime stage has no RUN steps,
# so building linux/arm64 on an x86 runner needs no emulation.

FROM --platform=$BUILDPLATFORM node:24-slim AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS jar
WORKDIR /src
COPY gradlew settings.gradle.kts ./
COPY gradle/ gradle/
COPY backend/ backend/
COPY --from=web /src/frontend/dist/ backend/src/main/resources/static/
RUN ./gradlew :backend:bootJar --no-daemon --console=plain && mkdir -p /out/data

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=jar /src/backend/build/libs/radiance.jar /app/radiance.jar
# data/ is a mounted volume in production; owned here so a plain `docker run` works too
COPY --from=jar --chown=1000:1000 /out/data/ /app/data/
USER 1000:1000
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# bash and /dev/tcp, so the image needs no curl (and no RUN)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/radiance.jar"]
