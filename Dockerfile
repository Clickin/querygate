# Multi-stage Dockerfile for QueryGate
FROM eclipse-temurin:25-jdk AS builder

# Set working directory
WORKDIR /build

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .

# Copy source code
COPY src src

# Build the application
RUN ./gradlew build -x test --no-daemon

# Extract the JAR file
RUN mkdir -p extracted && \
    java -Djarmode=layertools -jar build/libs/*.jar extract --destination extracted || \
    unzip -q build/libs/*.jar -d extracted

# Runtime stage
FROM eclipse-temurin:25-jre-alpine

# Install curl for healthcheck
RUN apk add --no-cache curl

# Create app user and group
RUN addgroup -g 1000 querygate && \
    adduser -u 1000 -G querygate -s /bin/sh -D querygate

# Set working directory
WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/build/libs/*.jar app.jar

# Create config directory
RUN mkdir -p /app/config && \
    chown -R querygate:querygate /app

# Switch to non-root user
USER querygate

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# JVM options
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+UseStringDeduplication"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
