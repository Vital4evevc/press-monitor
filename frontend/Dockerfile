# Build context is the REPO ROOT (see docker-compose.yml: context: ., dockerfile:
# frontend/Dockerfile), not this directory — that's so this stage can also reach
# ../shared-library, which frontend/pom.xml depends on.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# shared-library and frontend are independent Maven projects (no parent reactor), so
# press-monitor-shared has to be `mvn install`ed into the local repo before frontend's
# own build can resolve it.
COPY shared-library ./shared-library
RUN mvn -f shared-library/pom.xml -B -DskipTests install

COPY frontend/pom.xml ./frontend/pom.xml
COPY frontend/src ./frontend/src
RUN mvn -f frontend/pom.xml -B -DskipTests clean package

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/frontend/target/press-monitor-frontend-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
