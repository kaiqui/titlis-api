FROM gradle:8.10.2-jdk21-alpine AS build

WORKDIR /app

COPY build.gradle.kts ./
COPY settings.gradle.kts ./
COPY src ./src

RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/build/install/titlis-api ./titlis-api

EXPOSE 8080 8125/udp

ENTRYPOINT ["./titlis-api/bin/titlis-api"]
