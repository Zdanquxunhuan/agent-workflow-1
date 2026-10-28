FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/local-business-system-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
