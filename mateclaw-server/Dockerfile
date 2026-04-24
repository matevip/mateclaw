# Multi-stage build
#
# Stage 1 — Frontend (Node / pnpm)
#   Builds the Vue 3 admin SPA and emits static files to /static inside the
#   build container. These files are later copied into the JAR's classpath so
#   Spring Boot serves the SPA at the root URL.
FROM node:22-alpine AS frontend-builder
RUN npm install -g pnpm --silent
WORKDIR /frontend
# Install dependencies first (layer cache)
COPY mateclaw-ui/package.json mateclaw-ui/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# Copy source and build
COPY mateclaw-ui/ ./
# Override outDir: vite.config.ts writes to ../mateclaw-server/…/static which
# is outside this container; call vite directly to control --outDir.
# Skipping vue-tsc here is intentional — type errors are caught in CI, not in
# the production Docker image build.
RUN pnpm exec vite build --outDir /static --emptyOutDir

# Stage 2 — Backend (Maven)
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

# Copy backend source and inject pre-built frontend into the right classpath location
COPY mateclaw-server/src ./src
COPY --from=frontend-builder /static ./src/main/resources/static

RUN mvn package -DskipTests -q

# Stage 3 — Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 18088
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=mysql", "app.jar"]
