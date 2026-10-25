# One image for the whole app: the web app is built and packed into the backend jar, which serves both.
# Sized for small hosts (Render's free 512 MB instance): see JAVA_TOOL_OPTIONS below.

# ---- web app
FROM node:20-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- backend
FROM eclipse-temurin:21-jdk AS api
WORKDIR /api
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
# Windows checkouts may give the wrapper script CRLF line endings, which Linux can't run
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B -Dweb.skip=true dependency:go-offline
COPY backend/src src
COPY --from=web /web/dist src/main/resources/static
RUN ./mvnw -q -B -Dweb.skip=true -DskipTests package && cp target/clinic-emr-backend-*.jar app.jar

# ---- runtime
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=api /api/app.jar app.jar
USER app
# Keeps the whole process well under 512 MB; restart cleanly instead of limping on if memory runs out
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
