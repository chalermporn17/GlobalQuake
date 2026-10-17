# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Copy build files first for layer caching
COPY pom.xml ./
COPY GlobalQuakeAPI/pom.xml GlobalQuakeAPI/
COPY GlobalQuakeClient/pom.xml GlobalQuakeClient/
COPY GlobalQuakeCore/pom.xml GlobalQuakeCore/
COPY GlobalQuakeServer/pom.xml GlobalQuakeServer/
COPY libs ./libs

# Download dependencies (cached layer)
RUN mvn -q -B dependency:go-offline -pl GlobalQuakeServer -am || true

# Copy sources and build server jar-with-dependencies (client skipped: not needed)
COPY GlobalQuakeAPI/src GlobalQuakeAPI/src
COPY GlobalQuakeCore/src GlobalQuakeCore/src
COPY GlobalQuakeServer/src GlobalQuakeServer/src
RUN mvn -q -B package -DskipTests -pl GlobalQuakeServer -am

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-jammy
LABEL authors="xspanger3770"

RUN groupadd -g 1000 gq && useradd -u 1000 -g gq -m gq

WORKDIR /home/gq

COPY --from=build /src/GlobalQuakeServer/target/GlobalQuakeServer-*-jar-with-dependencies.jar ./GlobalQuakeServer.jar

# Server data folder (settings, station DB, archives, JSON dumps)
RUN mkdir -p ./.GlobalQuakeServerData && chown -R gq:gq /home/gq
USER gq

# Client socket API
EXPOSE 38000
# FDSNWS event API
EXPOSE 8080

# CPU-only hypocenter search (no CUDA lib shipped)
ENTRYPOINT ["java", "-Xmx4G", "-jar", "GlobalQuakeServer.jar", "--headless"]
