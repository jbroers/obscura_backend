# ---------- Build stage ----------
FROM gradle:8.4-jdk17-alpine AS build
WORKDIR /app

COPY gradlew gradle /app/
RUN chmod +x gradlew

COPY . /app
RUN chmod +x gradlew   
RUN ./gradlew --no-daemon build -x test


# ---------- Runtime stage ----------
FROM eclipse-temurin:20-jdk-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
