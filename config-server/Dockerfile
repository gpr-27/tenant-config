# build stage: the whole reactor so config-core is on the classpath
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY config-core/pom.xml config-core/
COPY config-client/pom.xml config-client/
COPY config-server/pom.xml config-server/
COPY booking-service-demo/pom.xml booking-service-demo/
COPY load-test/pom.xml load-test/
RUN mvn -q -B -pl config-server -am dependency:go-offline
COPY config-core config-core
COPY config-server config-server
RUN mvn -q -B -pl config-server -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/config-server/target/config-server-*-exec.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
