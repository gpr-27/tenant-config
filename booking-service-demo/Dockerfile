FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY config-core/pom.xml config-core/
COPY config-client/pom.xml config-client/
COPY config-server/pom.xml config-server/
COPY booking-service-demo/pom.xml booking-service-demo/
COPY load-test/pom.xml load-test/
RUN mvn -q -B -pl booking-service-demo -am dependency:go-offline
COPY config-core config-core
COPY config-client config-client
COPY booking-service-demo booking-service-demo
RUN mvn -q -B -pl booking-service-demo -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/booking-service-demo/target/booking-service-demo-*.jar app.jar
VOLUME /data
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
