# Build from the repository root: docker build -f backend-kotlin/Dockerfile .
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /source
COPY backend-kotlin/pom.xml backend-kotlin/pom.xml
COPY backend-kotlin/src backend-kotlin/src
# Keep the PHP migration as the single, byte-identical schema source.
COPY backend/migrations backend/migrations
RUN mvn -B -ntp -f backend-kotlin/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre-jammy@sha256:97137382c6f0c30427d9b7c44ad8b2d55ac823b0768c171d81a643ed219023c5 AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 rr \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin rr
WORKDIR /app
COPY --from=build --chown=10001:10001 /source/backend-kotlin/target/relationship-reset-api.jar /app/app.jar
USER 10001:10001
ENV RR_HOST=0.0.0.0 RR_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/health.php || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
