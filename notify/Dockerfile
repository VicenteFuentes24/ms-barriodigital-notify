FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package
COPY docker/HealthCheck.java docker/HealthCheck.java
RUN mkdir -p health && javac -d health docker/HealthCheck.java

FROM eclipse-temurin:21-jre
RUN groupadd --system notify && useradd --system --gid notify --home-dir /app notify \
    && mkdir -p /app/data && chown -R notify:notify /app
WORKDIR /app
COPY --from=build --chown=notify:notify /build/target/notify-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build --chown=notify:notify /build/health /app/health
USER notify
ENV NOTIFY_STORAGE_DIR=/app/data
EXPOSE 8087
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 CMD ["java", "-cp", "/app/health", "HealthCheck"]
ENTRYPOINT ["/bin/sh", "-c", "umask 0077; exec java -jar /app/app.jar"]
