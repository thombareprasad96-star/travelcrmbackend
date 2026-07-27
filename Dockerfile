# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests dependency:go-offline

COPY src src

RUN ./mvnw -B -DskipTests clean package \
    && cp target/travelcrm-*.jar /workspace/travelcrm.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

ARG DEBIAN_FRONTEND=noninteractive

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    TZ=Asia/Kolkata \
    LOG_DIR=/var/log/travelcrm \
    JAVA_OPTS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:MaxMetaspaceSize=512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8"

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system travelcrm \
    && useradd --system --gid travelcrm --home-dir /app --shell /usr/sbin/nologin travelcrm \
    && mkdir -p /var/log/travelcrm/archive \
    && chown -R travelcrm:travelcrm /app /var/log/travelcrm

COPY --from=build --chown=travelcrm:travelcrm /workspace/travelcrm.jar /app/travelcrm.jar

USER travelcrm

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/travelcrm.jar --server.address=${SERVER_ADDRESS:-0.0.0.0} --server.port=${SERVER_PORT:-8080} \"$@\"", "--"]
