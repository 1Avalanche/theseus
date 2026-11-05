# syntax=docker/dockerfile:1
# Образ сервиса Theseus (core или agent). Gradle внутри не запускается: контекст — корень репозитория
# после `./gradlew installDist`, в образ копируется готовый дистрибутив модуля.
#   docker build -f deploy/Dockerfile --build-arg MODULE=core -t theseus-core .
FROM eclipse-temurin:21-jre

ARG MODULE

# curl — для healthcheck core; ставится, только если его нет в базовом образе.
RUN command -v curl >/dev/null \
    || (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)

RUN groupadd --system theseus && useradd --system --gid theseus --home-dir /app theseus

WORKDIR /app
# Права явно: jar-файлы из Gradle-кеша могут быть 600 (зависит от umask машины сборки),
# и непривилегированный пользователь не прочитал бы их.
COPY --chmod=755 ${MODULE}/build/install/${MODULE}/ /app/
RUN ln -s "/app/bin/${MODULE}" /app/bin/start

USER theseus
# JAVA_OPTS (heap, GC) задаёт compose стенда.
ENTRYPOINT ["/app/bin/start"]
