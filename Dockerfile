FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS builder

USER root
RUN microdnf install -y findutils && microdnf clean all

USER quarkus
WORKDIR /project

# Стабильные TLS-настройки для загрузки зависимостей из Maven Central.
ENV JAVA_TOOL_OPTIONS="-Dhttps.protocols=TLSv1.2,TLSv1.3"
ENV GRADLE_OPTS="-Dhttps.protocols=TLSv1.2,TLSv1.3"

# 1) Копируем только файлы, влияющие на резолв зависимостей, чтобы улучшить кэш слоев.
COPY --chown=quarkus:quarkus --chmod=755 gradlew /project/gradlew
# Исправление окончаний строк (CRLF → LF) при клонировании на Windows.
RUN sed -i 's/\r$//' /project/gradlew
COPY --chown=quarkus:quarkus gradle /project/gradle
COPY --chown=quarkus:quarkus settings.gradle build.gradle gradle.properties /project/
RUN ./gradlew --no-daemon dependencies || true

# 2) Копируем исходники и собираем native бинарник Quarkus.
COPY --chown=quarkus:quarkus src /project/src
RUN ./gradlew --no-daemon clean build \
    -Dquarkus.native.enabled=true \
    -Dquarkus.package.jar.enabled=false \
    -x test -x spotlessCheck

# Копируем бинарник в предсказуемый путь для runtime-этапа.
RUN cp /project/build/*-runner /project/application

FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0 AS runtime

WORKDIR /work
COPY --from=builder /project/application /work/application
RUN chmod 755 /work/application && chmod 775 /work

# Non-root запуск для Kubernetes.
USER 1001
EXPOSE 8080

# Native процесс стартует быстро; слушаем все интерфейсы внутри контейнера.
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
