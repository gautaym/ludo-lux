# Ludo Lux game server. Build the jar first: ./gradlew :server:fatJar
FROM eclipse-temurin:17-jre-alpine
# Fixed uid/gid 10001 so the host folders (data, secrets) can be given to it: see setup-server.sh.
RUN addgroup -S -g 10001 lux && adduser -S -u 10001 lux -G lux && mkdir /data && chown lux:lux /data
COPY server/build/libs/ludolux-server.jar /app/ludolux-server.jar
# SQLite's native library, unpacked at build time: /tmp is mounted no-exec, so it cannot be unpacked there at run time.
RUN unzip -j /app/ludolux-server.jar "org/sqlite/native/Linux-Musl/$(uname -m)/libsqlitejdbc.so" -d /app/native
USER lux
WORKDIR /data
ENV PORT=8080 DB_PATH=/data/ludolux.db
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dorg.sqlite.lib.path=/app/native", "-Dorg.sqlite.lib.name=libsqlitejdbc.so", "-jar", "/app/ludolux-server.jar"]
