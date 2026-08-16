FROM clojure:temurin-21-tools-deps-alpine AS build
WORKDIR /app
COPY deps.edn build.clj ./
RUN clojure -P
COPY src src
COPY resources resources
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
