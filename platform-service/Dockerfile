# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21-alpine@sha256:5a8b906c4faa11d33f6c74758f67db8eac25441e14b0729f6d50ff78992be58a AS build

WORKDIR /workspace
COPY pom.xml ./
COPY platform-service/pom.xml platform-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 \
    -pl platform-service -am dependency:go-offline

COPY platform-service/src/main platform-service/src/main
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 \
    -pl platform-service -am package -Dmaven.test.skip=true \
    && cp platform-service/target/platform-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699

RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
