# syntax=docker/dockerfile:1.7
# Multi-stage build for eureka-server. Build context is the REPOSITORY ROOT (the parent POM and the shared
# contracts / internal-security modules are needed):
#   docker build -f eureka-server/Dockerfile -t thermogrid/eureka-server .
# Stage 1 compiles only this module and what it depends on; stage 2 is a small JRE image with the jar only.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl eureka-server -am -DskipTests package \
 && cp eureka-server/target/eureka-server-*.jar /app.jar

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S tg && adduser -S tg -G tg && mkdir -p /app/data && chown -R tg:tg /app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER tg
# Heap cap about 256 MB per JVM (plan section 12, phase 12); override with JAVA_OPTS. The container memory limit is
# set in infra/docker-compose.full.yml. No secret is baked in: everything comes from environment variables.
ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8761
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
