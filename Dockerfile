# syntax=docker/dockerfile:1

# ---- Stage 1: build the boot jar ----
# Full JDK + the Gradle wrapper. Dependencies are resolved in their own layer so
# they are cached across builds as long as the build scripts don't change.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 1) copy only what's needed to resolve dependencies -> cache this layer
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle* ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 2) copy the source and build. Tests need a Docker daemon (Testcontainers) that
#    isn't available inside the image build, so they run in CI, not here.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Stage 2: minimal runtime ----
# JRE only (no compiler/build tooling) -> smaller, smaller attack surface.
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# run as a non-root user
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
