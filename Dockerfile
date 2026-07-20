FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :engine:cli:installDist --no-daemon --no-configuration-cache

FROM eclipse-temurin:17-jre
RUN useradd --create-home --uid 10001 evomolior
WORKDIR /opt/evomolior
COPY --from=builder /workspace/evomolior/cli/build/install/evomolior /opt/evomolior
RUN mkdir -p /data && chown -R evomolior:evomolior /data /opt/evomolior
USER evomolior
ENV EVOMOLIOR_DASHBOARD_HOST=0.0.0.0 \
    EVOMOLIOR_DASHBOARD_PORT=8080 \
    EVOMOLIOR_DASHBOARD_DATA_DIR=/data \
    EVOMOLIOR_CLI_BIN=/opt/evomolior/bin/evomolior \
    PATH=/opt/evomolior/bin:$PATH
VOLUME ["/data"]
EXPOSE 8080
CMD ["evomolior", "serve"]
