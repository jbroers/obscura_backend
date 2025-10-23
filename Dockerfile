# ---------- Build stage ----------
FROM gradle:8.4-jdk17-alpine AS build
WORKDIR /app

# Kopieer alleen build-bestanden eerst voor caching
COPY gradlew gradle /app/

# Download dependencies (cache-laag)
RUN ./gradlew --no-daemon build || true

# Kopieer volledige project en bouw
COPY . /app
RUN ./gradlew --no-daemon build -x test

# ---------- Runtime stage ----------
FROM eclipse-temurin:20-jdk-alpine
WORKDIR /app

# Kopieer alleen de JAR uit de build stage
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
