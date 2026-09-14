# (senza BuildKit: compatibile col builder legacy) =============================================================================
# E-Shop — multi-stage build
#   stage 1: build JAR con Maven
#   stage 2: runtime minimale (JRE 21, utente non-root)
# =============================================================================

# ── Build ────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline -q || true

COPY src ./src
RUN mvn -B clean package -DskipTests -q

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
# Utente non-root + directory per le immagini articoli (montata come volume in K8s)
RUN useradd --system --create-home eshop \
    && mkdir -p /app/data/article-images \
    && chown -R eshop:eshop /app
WORKDIR /app

COPY --from=build /build/target/eshop-0.0.1-SNAPSHOT.jar app.jar

USER eshop
EXPOSE 8081
# Il limite di memoria JVM segue i limit K8s (75% del limit)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java", "-jar", "app.jar"]
