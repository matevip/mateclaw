# Multi-stage build
FROM maven:3.9-eclipse-temurin-21 AS builder

# Inject mirror settings to avoid Maven Central timeouts in restricted networks
COPY mateclaw-server/settings.xml /root/.m2/settings.xml

# Build and install plugin-api into the local Maven cache first
WORKDIR /plugin-api
COPY mateclaw-plugin-api/pom.xml ./pom.xml
COPY mateclaw-plugin-api/src ./src
RUN mvn install -DskipTests -q

# Pre-fetch mateclaw-server dependencies (uses mirror, so this won't hang)
WORKDIR /build
COPY mateclaw-server/pom.xml .
RUN mvn dependency:go-offline -q

# Build the final JAR
COPY mateclaw-server/src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 18088
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=mysql", "app.jar"]
