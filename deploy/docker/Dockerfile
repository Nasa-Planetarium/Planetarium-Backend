# Runtime image for the EMBERLINE backend. Build context = repo root after `./gradlew --offline bootJar`.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app app && mkdir -p /app && chown app /app
COPY build/libs/emberline.jar /app/emberline.jar
USER 10001
WORKDIR /app
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Djava.io.tmpdir=/tmp", "-jar", "/app/emberline.jar"]
