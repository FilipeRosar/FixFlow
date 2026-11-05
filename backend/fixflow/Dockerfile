
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .
COPY config ./config
COPY src ./src

RUN mvn -B --no-transfer-progress clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine-3.24

RUN apk upgrade --no-cache

WORKDIR /app

COPY --from=build /app/target/fixflow-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
