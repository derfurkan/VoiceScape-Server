FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew && ./gradlew clean build -x check -x test -Pproduction

EXPOSE 8080

CMD ["java", "-jar", "-Dserver.port=8080", "build/libs/server.jar"]
