## Quarkus Rate Limiting API

Quarkus-приложение на Gradle с ограничением количества обрабатываемых запросов через rate limiting.

### Структура проекта

- **Код**: `src/main/java/org/example/`
  - `WorkResource.java` - REST-эндпоинты с rate limiting
- **Конфигурация**: `src/main/resources/application.properties`
- **Сборка**: Gradle (`build.gradle`, `settings.gradle`)

### Конфигурация rate limiting

В `application.properties` настраивается лимит запросов:

```properties
app.api.limit=${APP_API_LIMIT:70}
app.api.timeout=${APP_API_TIMEOUT:600}
```

- `app.api.limit` — максимальное количество одновременных запросов (по умолчанию 70)
- `app.api.timeout` — задержка обработки запроса в миллисекундах (по умолчанию 600)

При превышении лимита возвращается HTTP 429 (Too Many Requests).

### Эндпоинты

- `GET /work` - основная точка для нагрузочного теста. Обрабатывает запрос с задержкой и возвращает `OK` при успехе, либо `429` при превышении лимита.
- `GET /work/status` - возвращает текущее количество активных запросов.
- `GET /q/metrics` - метрики в формате Prometheus (в т.ч. кастомная `work_active_requests` для HPA).

### Swagger UI

Приложение включает поддержку Swagger/OpenAPI для документирования и тестирования API.

После запуска приложения доступны:

- **Swagger UI**: `http://localhost:8080/q/swagger-ui` - интерактивная документация и тестирование API
- **OpenAPI спецификация (JSON)**: `http://localhost:8080/q/openapi` - OpenAPI спецификация в формате JSON

### Запуск сервиса

Из корня репозитория:

```bash
./gradlew quarkusDev
```

Сервис поднимется на `http://localhost:8080` (порт настраивается в `application.properties`).

### Сборка и запуск в production режиме

```bash
# Сборка JAR
./gradlew build

# Запуск собранного приложения
java -jar build/quarkus-app/quarkus-run.jar

# Сборка в нативном режиме
./gradlew --no-daemon --build-cache build -x test -x spotlessJavaApply -x spotlessJava -Dquarkus.native.enabled=true -Dquarkus.native.remote-container-build=false -Dquarkus.package.jar.enabled=false
```

### Docker Compose

Сервис **api** (Quarkus) описывается в `docker-compose.yml`. Переменные `APP_API_LIMIT` и `APP_API_TIMEOUT` задаются через файл `.env` или через переменные окружения при запуске (не захардкожены в compose).

1. Скопируйте пример и при необходимости отредактируйте значения:
   ```bash
   cp .env.example .env
   ```
2. Запуск:
   ```bash
   docker compose up --build -d
   ```

**Healthcheck:** раз в 10 секунд выполняется запрос к `GET /work/status`. Контейнер считается неработоспособным (unhealthy), если возвращённое значение (число активных запросов) больше `APP_API_LIMIT`.

### Масштабирование и балансировщик Traefik

Входящий HTTP-трафик принимает **Traefik** на порту **80** хоста и распределяет запросы между экземплярами **api** по round-robin.

1. Запуск с тремя экземплярами api и Traefik:
   ```bash
   cp .env.example .env   # при необходимости
   docker compose up --build -d --scale api=3
   ```
2. Доступ к приложению — через балансировщик: `http://localhost:80` (или `http://localhost/work`).
3. Дашборд Traefik (опционально): `http://localhost:8081/dashboard/`.

Генератор нагрузки нужно направлять на Traefik (порт 80), а не на контейнеры api. Пример нагрузочного теста см. ниже.

### Kubernetes и мониторинг

- Развёртывание в Kubernetes: [docs/KUBERNETES-SETUP.md](docs/KUBERNETES-SETUP.md)
- Prometheus Stack, ServiceMonitor для `/q/metrics` и prometheus-adapter для HPA (метрика `work_active_requests`): [docs/MONITORING-SETUP.md](docs/MONITORING-SETUP.md)

### Теоретический RPS и нагрузочное тестирование

**Расчёт теоретического RPS:** каждый запрос занимает «слот» на время `APP_API_TIMEOUT` мс. Один слот даёт не более `1000 / APP_API_TIMEOUT` запросов в секунду. При `APP_API_LIMIT` слотах:

**RPS_theory = APP_API_LIMIT × (1000 / APP_API_TIMEOUT)**

Пример: `APP_API_LIMIT=70`, `APP_API_TIMEOUT=600` мс → RPS_theory = 70 × (1000/600) ≈ **116,7** запросов/с. Ожидается, что при RPS ≤ этого значения доля ошибок (429 и др.) не превысит 5%.

**Проверка нагрузочным тестом (load-tester в Docker):**

1. Соберите образ load-tester (один раз):  
   `docker build -f load-tester/Dockerfile -t load-tester ./load-tester`

2. **Один инстанс api** (порт 8080): запуск `docker compose up -d api`, тест на 15 с:
   ```bash
   docker run --rm --add-host=host.docker.internal:host-gateway load-tester -url=http://host.docker.internal:8080/work -rps=115 -duration=15s
   ```

3. **Три инстанса api за Traefik** (порт 80): запуск `docker compose up -d --scale api=3`, затем нагрузочный тест **не менее 60 секунд** с интенсивностью **выше возможностей одного инстанса** (например RPS_theory одного ≈ 117, для трёх ≈ 350; целевой RPS 300–350):
   ```bash
   docker run --rm --add-host=host.docker.internal:host-gateway load-tester -url=http://host.docker.internal:80/work -rps=320 -duration=60s
   ```
   На Linux можно использовать `--network host` и `-url=http://127.0.0.1:80/work`.

4. В отчёте проверьте «Успешность» (доля успешных 2xx). Критерий: **доля ошибок не более 5%** (успешность ≥ 95%) при RPS ≤ суммарного RPS_theory по всем инстансам.
