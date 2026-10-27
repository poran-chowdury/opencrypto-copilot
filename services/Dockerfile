# One Dockerfile for every Java service: docker build --build-arg SERVICE=wallet-service -f services/Dockerfile .
# Stage 1 builds only that module (+ the shared library) with a cached Maven repository.
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /workspace
COPY pom.xml .
COPY libs libs
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/${SERVICE} -am -DskipTests package \
 && cp services/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /workspace/app.jar

# Stage 2: small JRE image, non-root user, container-aware heap sizing.
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
