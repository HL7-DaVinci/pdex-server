# Multi-stage Dockerfile for FHIR Server with TanStack SPA Frontend

##########################################################################
# Stage 1: Build the TanStack SPA frontend
##########################################################################
FROM oven/bun:1-slim AS build-frontend

WORKDIR /app

# Copy workspace package files and lockfile
COPY package.json bun.lock ./
COPY frontend/package.json ./frontend/

# Install dependencies
RUN bun install --frozen-lockfile

# Copy frontend source files for build
WORKDIR /app
COPY frontend/index.html ./frontend/
COPY frontend/tsconfig.json ./frontend/
COPY frontend/vite.config.ts ./frontend/
COPY frontend/public/ ./frontend/public/
COPY frontend/src/ ./frontend/src/

# Build the frontend for production
WORKDIR /app/frontend
RUN bun run build

##########################################################################
# Stage 2: Build the HAPI FHIR Server
##########################################################################
FROM docker.io/library/maven:3.9.12-eclipse-temurin-17 AS build-server

WORKDIR /tmp/hapi-fhir-jpaserver-starter

# Download OpenTelemetry agent
ARG OPENTELEMETRY_JAVA_AGENT_VERSION=2.24.0
RUN curl -LSsO https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OPENTELEMETRY_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar

# Copy Maven configuration and download dependencies
COPY server/pom.xml .
COPY server/server.xml .
RUN mvn -ntp dependency:go-offline

# Copy server source code
COPY server/src/ /tmp/hapi-fhir-jpaserver-starter/src/

# Copy frontend build artifacts to server's static resources directory
COPY --from=build-frontend /app/frontend/dist/ /tmp/hapi-fhir-jpaserver-starter/src/main/resources/static/

# Build the server
RUN mvn clean install -DskipTests -Djdk.lang.Process.launchMechanism=vfork

##########################################################################
# Stage 3: Package for Spring Boot
##########################################################################
FROM build-server AS build-distroless
RUN mvn package -DskipTests spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /app/main.war

COPY server/src/main/java/HealthCheck.java /app/HealthCheck.java
RUN javac /app/HealthCheck.java

##########################################################################
# Stage 4: Tomcat Image, built with `docker build --target tomcat .`
##########################################################################
FROM docker.io/library/tomcat:10-jre21-temurin-noble AS tomcat

USER root
RUN rm -rf /usr/local/tomcat/webapps/ROOT && \
    mkdir -p /usr/local/tomcat/data/hapi/lucenefiles && \
    chown -R 65532:65532 /usr/local/tomcat/data/hapi/lucenefiles && \
    chmod 775 /usr/local/tomcat/data/hapi/lucenefiles

RUN mkdir -p /target && chown -R 65532:65532 /target
USER 65532

COPY --chown=65532:65532 server/catalina.properties /usr/local/tomcat/conf/catalina.properties
COPY --chown=65532:65532 server/server.xml /usr/local/tomcat/conf/server.xml
COPY --from=build-server --chown=65532:65532 /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
COPY --from=build-server --chown=65532:65532 /tmp/hapi-fhir-jpaserver-starter/opentelemetry-javaagent.jar /app

##########################################################################
# Stage 5: Final Production Image (Distroless)
##########################################################################
FROM gcr.io/distroless/java21-debian13:nonroot AS default

USER 65532:65532
WORKDIR /app

COPY --chown=nonroot:nonroot --from=build-distroless /app /app
COPY --chown=nonroot:nonroot --from=build-server /tmp/hapi-fhir-jpaserver-starter/opentelemetry-javaagent.jar /app

EXPOSE 8080

ENTRYPOINT ["java", "--class-path", "/app/main.war", "-Dloader.path=main.war!/WEB-INF/classes/,main.war!/WEB-INF/,/app/extra-classes", "org.springframework.boot.loader.PropertiesLauncher"]
