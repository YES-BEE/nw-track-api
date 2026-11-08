FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:25-jre
USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 nwtrack
RUN mkdir -p /var/log/nwtrack && chown nwtrack:nwtrack /var/log/nwtrack
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER nwtrack
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
