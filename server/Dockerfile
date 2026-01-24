# Multi-stage build: build with Gradle, run with lightweight JRE
FROM gradle:8.4-jdk17 AS build

# Keep ownership sane inside the container
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . /home/gradle/project

# Build server module (skip tests in image build for speed)
RUN ./gradlew :server:clean :server:build -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy built artifacts
COPY --from=build /home/gradle/project/server/build/libs/ /app/libs/

# Expose standard port
EXPOSE 8787

# Run the first jar in libs directory (adjust if multiple jars exist)
ENTRYPOINT ["sh", "-c", "java -jar /app/libs/$(ls /app/libs | head -n 1)"]