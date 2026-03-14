# Локальный Kubernetes (Minikube) и Ingress

## Часть 1. Создание кластера Minikube

### 1.1. Установка Minikube

**macOS (Homebrew):**
```bash
brew install minikube
```

**Или скачайте бинарник:** https://minikube.sigs.k8s.io/docs/start/

Проверка:
```bash
minikube version
```

Также нужен **kubectl** (клиент для управления кластером):
```bash
brew install kubectl
# или: minikube kubectl -- get pods -A  (minikube может использовать встроенный kubectl)
```

### 1.2. Запуск кластера с поддержкой Ingress

Запустите кластер с драйвером **docker** (рекомендуется на macOS) или **hyperkit**:

```bash
minikube start --driver=docker
```

Чтобы порты 80 и 443 были доступны на локальной машине через Ingress, нужно включить **Ingress addon**:

```bash
minikube addons enable ingress
```

Проверка:
```bash
kubectl get pods -n ingress-nginx
```
Должны быть поды контроллера Ingress (ingress-nginx-controller и т.п.).

### 1.3. Что такое Ingress и зачем он нужен

**Ingress** в Kubernetes — это объект, который описывает **правила входа HTTP/HTTPS-трафика** в кластер извне:

- **Без Ingress:** к приложению внутри кластера можно попасть только через `kubectl port-forward` или через NodePort (порты 30000–32767). Удобно для разработки, но не для «нормального» доступа по 80/443.
- **С Ingress:** на портах 80 и 443 слушает **Ingress Controller** (например, NGINX). Он читает правила из ресурсов **Ingress** (какой Host/path куда направлять) и проксирует запросы на нужные **Service** внутри кластера.

То есть:
1. Запрос приходит на `http://localhost` (порт 80) или `https://localhost` (443).
2. Ingress Controller принимает запрос и смотрит правила Ingress (host, path).
3. Трафик перенаправляется на соответствующий Service (ваше приложение).

Так вы получаете доступ к приложениям извне по стандартным портам 80/443 без ручного port-forward.

### 1.4. Доступ по 80/443 на локальной машине

В Minikube при включённом addon `ingress` контроллер обычно слушает внутри виртуальной сети. Чтобы порты 80/443 были на **localhost** вашей машины, используйте туннель:

```bash
minikube tunnel
```

Запустите эту команду в **отдельном терминале** и оставьте работающей. Она создаёт туннель и пробрасывает порты 80/443 Minikube на ваш localhost. (Может потребоваться ввод пароля для доступа к сетевому интерфейсу.)

Альтернатива — использовать `minikube service` для NodePort-сервисов, но для доступа именно по 80/443 удобнее **Ingress + tunnel**.

Проверка после запуска туннеля:
```bash
curl -H "Host: work.local" http://localhost/work/status
```
(Если Ingress настроен на host `work.local` и path `/work`, как в примере манифестов ниже.)

---

## Часть 2. Сборка образа и загрузка в кластер

Minikube по умолчанию использует свой собственный Docker daemon. Чтобы образ был виден внутри кластера, его нужно либо собрать **внутри** окружения Minikube, либо **загрузить** в него после сборки.

### Вариант A: Сборка образа внутри Minikube (рекомендуется)

Тогда образ сразу будет в «локальном» registry Minikube:

```bash
# Переключить текущую консоль на использование docker из minikube
eval $(minikube docker-env)

# Собрать образ (из корня репозитория)
docker build -t ci-cd-example-app:latest .

# Вернуть обычный docker (опционально)
# eval $(minikube docker-env -u)
```

Образ `ci-cd-example-app:latest` будет виден в кластере, можно использовать в манифестах `image: ci-cd-example-app:latest` и `imagePullPolicy: Never` (чтобы не тянуть из внешнего registry).

### Вариант B: Сборка на хосте и загрузка в Minikube

Если собираете образ обычным `docker build` на своей машине:

```bash
# Сборка на хосте
docker build -t ci-cd-example-app:latest .

# Загрузить образ в кластер Minikube
minikube image load ci-cd-example-app:latest
```

В манифестах укажите тот же тег и `imagePullPolicy: Never`.

### Вариант C: Использовать внешний registry

Собрать образ, push в Docker Hub (или другой registry), в манифестах указать полное имя образа и убрать `imagePullPolicy: Never`. Тогда кластер будет скачивать образ из интернета (для Minikube это тоже работает).

---

## Часть 2 (задание). Развертывание в Kubernetes: Deployment и Service

### 4. Манифесты Deployment и Service

**Deployment** (`k8s/deployment.yaml`):
- Начальное количество реплик: **1** (`replicas: 1`).
- Образ: **ci-cd-example-app:latest** (собранный ранее).
- Переменные окружения по варианту задания: **APP_API_LIMIT** (по умолчанию 70), **APP_API_TIMEOUT** (по умолчанию 600). Приложение читает их из `application.properties`.
- Поды помечены меткой `app: ci-cd-example-app` для связи с Service.

**Service** (`k8s/service.yaml`):
- Тип: **ClusterIP** — доступ к подам только внутри кластера (внутренний DNS и порт).
- Имя сервиса: `ci-cd-example-app`. Порт сервиса 80 маппится на `targetPort: 8080` контейнера.
- Селектор `app: ci-cd-example-app` направляет трафик на поды Deployment.

При необходимости изменить лимит/таймаут — отредактируйте блок `env` в `k8s/deployment.yaml` и выполните `kubectl apply -f k8s/deployment.yaml`.

---

## Часть 3. Применение манифестов и проверка

### 5. Применение манифестов

Из корня репозитория (где лежит папка `k8s/`):

```bash
# Применить Deployment и Service (обязательно для п.5 задания)
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# Если используете Ingress — применить и его
kubectl apply -f k8s/ingress.yaml
```

Или одной командой:
```bash
kubectl apply -f k8s/
```

При наличии манифеста HPA (`k8s/hpa.yaml`) он тоже будет применён. Для работы HPA по метрике `work_active_requests` нужны Prometheus Stack и prometheus-adapter (см. [MONITORING-SETUP.md](MONITORING-SETUP.md)).

Ожидаемый вывод (без HPA уже развёрнутого мониторинга):
```
deployment.apps/ci-cd-example-app created
service/ci-cd-example-app created
ingress.networking.k8s.io/ci-cd-example-app created
horizontalpodautoscaler.autoscaling/ci-cd-example-app created
```

### 5.1. Убедиться, что под запустился

```bash
kubectl get pods -l app=ci-cd-example-app
```

Под должен быть в состоянии **Running** и **Ready 1/1**. Если статус `ContainerCreating` или `Pending`, подождите несколько секунд или проверьте события:

```bash
kubectl describe pod -l app=ci-cd-example-app
kubectl get events --sort-by='.lastTimestamp'
```

### 5.2. Проверка, что приложение отвечает внутри кластера

Доступ к приложению **внутри кластера** идёт через Service по DNS-имени `ci-cd-example-app` (в том же namespace) или `ci-cd-example-app.<namespace>.svc.cluster.local`.

**Способ 1 — временный под с curl:**

```bash
kubectl run curl-test --rm -it --restart=Never --image=curlimages/curl -- curl -s http://ci-cd-example-app:80/work/status
```

Ожидаемый ответ: число (текущее количество активных запросов), например `0`.

**Способ 2 — port-forward и запрос с хоста:**

```bash
kubectl port-forward svc/ci-cd-example-app 9080:80
```

В другом терминале:
```bash
curl http://localhost:9080/work/status
curl http://localhost:9080/work
```

После проверки прервите port-forward (Ctrl+C).

Так вы убеждаетесь, что под запустился и приложение отвечает на запросы внутри кластера через Service (п.5 задания).

---

## Часть 3 (задание). Настройка балансировки через Ingress

### 6. Установка Ingress Controller

В Minikube Ingress-контроллер (NGINX Ingress Controller) включается встроенным addon:

```bash
minikube addons enable ingress
```

Проверка, что контроллер запущен:
```bash
kubectl get pods -n ingress-nginx
```
Должны быть поды в состоянии Running (например `ingress-nginx-controller-...`).

На других окружениях (не Minikube) NGINX Ingress Controller ставят, например, так:  
`kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.x/deploy/static/provider/cloud/deploy.yaml` (актуальный URL см. в [документации](https://kubernetes.github.io/ingress-nginx/deploy/)).

### 7. Создание Ingress-ресурса

Манифест **`k8s/ingress.yaml`** уже описывает Ingress:
- Доменное имя: **work.local**
- Все HTTP-запросы на этот хост направляются на Service **ci-cd-example-app** (порт 80).
- Путь запроса **сохраняется без изменений** (например `/work`, `/work/status` попадают в приложение как есть).

Применить:
```bash
kubectl apply -f k8s/ingress.yaml
```

Резолвинг **work.local** в IP узла кластера (на вашей машине): добавьте запись в файл **hosts**:

```bash
# macOS / Linux — отредактировать с правами sudo
sudo nano /etc/hosts
```

Добавьте строку (для доступа через туннель Minikube подойдёт 127.0.0.1):
```
127.0.0.1 work.local
```

Сохраните файл. После запуска `minikube tunnel` (см. ниже) запросы к `http://work.local` будут уходить на localhost:80, который туннель пробрасывает в Ingress Controller.

### 8. Проверка доступности приложения извне

1. Запустите туннель, чтобы порты 80/443 кластера были доступны на localhost (в **отдельном терминале**):
   ```bash
   minikube tunnel
   ```
   Оставьте команду работающей.

2. Выполните HTTP-запрос к приложению через Ingress:

   **Через браузер:** откройте `http://work.local/work/status` и `http://work.local/work`.  
   **Через curl:**
   ```bash
   curl http://work.local/work/status
   curl http://work.local/work
   ```
   Ожидаемо: в ответ на `/work/status` — число (активные запросы), на `/work` — ответ приложения (например «OK» при успешной обработке).

3. Убедитесь, что ответы приходят от вашего приложения (тот же формат, что и при обращении внутри кластера или через port-forward). Так выполняется п.8 задания — проверка доступности извне через настроенный Ingress.

**Если туннель не используете** (например, тестируете только внутри кластера), можно проверить через заголовок Host:
```bash
curl -H "Host: work.local" http://127.0.0.1/work/status
```
(при условии, что порт 80 на 127.0.0.1 слушает Ingress после `minikube tunnel`.)

---

## Пересборка образа и перезапуск в кластере

После изменений в коде приложения нужно пересобрать образ и перезапустить поды:

```bash
# 1. Сборка образа в окружении Minikube (образ сразу будет виден в кластере)
eval $(minikube docker-env)
docker build -t ci-cd-example-app:latest .

# 2. Перезапуск Deployment — старые поды завершатся, новые поднимутся с обновлённым образом
kubectl rollout restart deployment/ci-cd-example-app
```

Дождаться готовности новых подов:

```bash
kubectl rollout status deployment/ci-cd-example-app
```

Если образ собирали на хосте (не через `minikube docker-env`), после сборки загрузите его в Minikube, затем перезапустите деплой:

```bash
docker build -t ci-cd-example-app:latest .
minikube image load ci-cd-example-app:latest
kubectl rollout restart deployment/ci-cd-example-app
```

---

## Краткая шпаргалка команд

| Действие | Команда |
|----------|--------|
| Запуск кластера | `minikube start --driver=docker` |
| Включить Ingress | `minikube addons enable ingress` |
| Туннель 80/443 на localhost | `minikube tunnel` (в отдельном терминале) |
| Сборка в окружении Minikube | `eval $(minikube docker-env)` затем `docker build -t ci-cd-example-app:latest .` |
| Загрузка образа в Minikube | `minikube image load ci-cd-example-app:latest` |
| **Перезапуск после пересборки** | `kubectl rollout restart deployment/ci-cd-example-app` |
| Применить манифесты | `kubectl apply -f k8s/` |
| Остановить кластер | `minikube stop` |
| Удалить кластер | `minikube delete` |
