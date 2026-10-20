# Ludo Lux game server. Build the jar first: ./gradlew :server:fatJar
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S lux && adduser -S lux -G lux && mkdir /data && chown lux:lux /data
COPY server/build/libs/ludolux-server.jar /app/ludolux-server.jar
USER lux
WORKDIR /data
ENV PORT=8080 DB_PATH=/data/ludolux.db
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/ludolux-server.jar"]
