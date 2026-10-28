# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble@sha256:a9c5ddd386f629518bd500ecfabf7d1e5d32d7ffed735d5c3b1604ae92c830f4 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY --chmod=0755 mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw
COPY src/ src/
COPY docker/postgres/ docker/postgres/
# The default suite uses H2. Docker/Testcontainers are validated separately.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp clean verify
COPY docker/Healthcheck.java /tmp/Healthcheck.java
RUN javac --release 25 -d /tmp/health /tmp/Healthcheck.java

# Optional export of the actual Java 25 test reports; never part of the runtime.
FROM scratch AS test-results
COPY --from=build /workspace/target/surefire-reports/ /surefire-reports/
COPY --from=build /workspace/target/site/jacoco/ /jacoco/

FROM eclipse-temurin:25-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f AS runtime
RUN groupadd --gid 10001 servicehub && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin servicehub
WORKDIR /opt/servicehub
COPY --from=build --chown=10001:10001 /workspace/target/servicehub-api-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build --chown=10001:10001 /tmp/health/ health/
ENV SPRING_PROFILES_ACTIVE=prod SERVER_PORT=8080
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 CMD ["java", "-Xms8m", "-Xmx32m", "-cp", "/opt/servicehub/health", "Healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/opt/servicehub/app.jar"]
