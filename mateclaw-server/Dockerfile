# 多阶段构建
FROM maven:3.9-eclipse-temurin-21 AS builder

# 先构建并安装 plugin-api 到本地 Maven 缓存
WORKDIR /plugin-api
COPY mateclaw-plugin-api/pom.xml ./pom.xml
COPY mateclaw-plugin-api/src ./src
RUN mvn install -DskipTests -q

# 再构建 mateclaw-server
WORKDIR /build
COPY mateclaw-server/pom.xml .
RUN mvn dependency:go-offline -q
COPY mateclaw-server/src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 18088
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=mysql", "app.jar"]
