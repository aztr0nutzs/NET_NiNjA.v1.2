# Multi-stage build: build with Gradle, run with lightweight JRE
FROM gradle:8.4-jdk21 AS build

# Keep ownership sane inside the container
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . /home/gradle/project

# Build server distribution (includes runtime classpath)
RUN ./gradlew :server:clean :server:installDist -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy built distribution + web UI assets
COPY --from=build /home/gradle/project/server/build/install/server/ /app/
COPY --from=build /home/gradle/project/web-ui/ /app/web-ui/

# Expose standard port
EXPOSE 8787

# Run the server launcher script (application plugin)
ENTRYPOINT ["/app/bin/server"]
