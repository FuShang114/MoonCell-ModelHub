FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN useradd -r -u 1001 appuser

# 创建日志目录，Logback 不会自动创建父目录，需预先创建
RUN mkdir -p /app/logs && chown -R appuser:appuser /app/logs

COPY --from=builder /app/target/*.jar /app/app.jar

EXPOSE 9061
USER appuser

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
