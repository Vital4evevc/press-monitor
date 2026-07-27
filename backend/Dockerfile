# Build context is the REPO ROOT (see docker-compose.yml: context: ., dockerfile:
# backend/Dockerfile), not this directory — that's so this stage can also reach
# ../shared-library, which backend/pom.xml depends on.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# shared-library and backend are independent Maven projects (no parent reactor), so
# press-monitor-shared has to be `mvn install`ed into the local repo before backend's
# own build can resolve it.
COPY shared-library ./shared-library
RUN mvn -f shared-library/pom.xml -B -DskipTests install

COPY backend/pom.xml ./backend/pom.xml
COPY backend/src ./backend/src
RUN mvn -f backend/pom.xml -B -DskipTests clean package

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/backend/target/press-monitor-backend-1.0.0.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
