FROM gradle:9.4.1-jdk21-alpine AS build

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle --no-daemon dependencies --configuration runtimeClasspath -q

COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/build/install/titlis-api ./titlis-api

EXPOSE 8080 8125/udp

ENTRYPOINT ["./titlis-api/bin/titlis-api"]
