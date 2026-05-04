FROM gradle:9.4.1-jdk21-alpine AS build

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle --no-daemon dependencies --configuration runtimeClasspath -q

COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre-alpine AS dd-download
RUN apk add --no-cache wget && \
    wget -q -O /dd-java-agent.jar "https://dtdg.co/latest-java-tracer"

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/build/install/titlis-api ./titlis-api
COPY --from=dd-download /dd-java-agent.jar /app/dd-java-agent.jar

ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+ExitOnOutOfMemoryError -javaagent:/app/dd-java-agent.jar"

EXPOSE 8080 8125/udp

ENTRYPOINT ["./titlis-api/bin/titlis-api"]
