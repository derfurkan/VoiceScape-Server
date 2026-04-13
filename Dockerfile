FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew && ./gradlew clean build -x check -x test -Pproduction

EXPOSE 5555

CMD ["java", "-jar", "-Dserver.port=5555", "build/libs/server.jar"]
