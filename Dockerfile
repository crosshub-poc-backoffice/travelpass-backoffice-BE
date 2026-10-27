# BE 이미지. JDK 17 로 빌드하고 JRE 17 로 실행한다 — 맥에 Java 17 이 없어도 된다.
# 로컬: compose.yaml 이 이 파일로 만든다 (docker compose --profile app up -d --build). 설정은 전부 환경변수로 받는다 (.env.example)

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# 의존성 먼저 받아 둔다 — 소스만 바뀌면 이 층은 다시 받지 않는다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v plain)" /app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
