# Мониторинг и подготовка к автомасштабированию (HPA)

Документ описывает установку Prometheus Stack, настройку сбора метрик с приложения `work-app` (ci-cd-example-app) и настройку prometheus-adapter для использования кастомной метрики `work_active_requests` в HPA.

## Предварительные требования

- Kubernetes-кластер (например, Minikube)
- Helm 3
- Приложение развёрнуто в кластере (см. [KUBERNETES-SETUP.md](KUBERNETES-SETUP.md))

## Установка Helm

Если Helm ещё не установлен:

**macOS (Homebrew):**
```bash
brew install helm
```

**macOS / Linux (официальный скрипт):**
```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

**Проверка:**
```bash
helm version
```
Должна отображаться версия 3.x (например `version.BuildInfo{Version:"v3.14.0", ...}`).

## 1. Установка Prometheus Stack (kube-prometheus-stack)

Устанавливается Prometheus, Alertmanager и при необходимости Grafana.

```bash
# Добавить репозиторий Helm
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# Создать namespace для мониторинга
kubectl create namespace monitoring

# Установить стек с настройкой обнаружения ServiceMonitors во всех namespace
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f k8s/monitoring/kube-prometheus-stack-values.yaml
```

Параметры `serviceMonitorSelectorNilUsesHelmValues: false` и `serviceMonitorNamespaceSelector: {}` нужны, чтобы Prometheus подхватывал наш ServiceMonitor из namespace `default`.

Дождитесь готовности подов:

```bash
kubectl -n monitoring get pods -w
```

Опционально: доступ к Grafana (логин по умолчанию `admin`, пароль в секрете):

```bash
kubectl -n monitoring get secret kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d
```

## 2. Настройка сбора метрик с приложения (ServiceMonitor)

Приложение экспортирует метрики на эндпоинте `/q/metrics` (в т.ч. кастомную метрику `work_active_requests`). ServiceMonitor указывает Prometheus, откуда их забирать.

Убедитесь, что приложение и его Service развёрнуты в namespace `default`, затем примените ServiceMonitor:

```bash
kubectl apply -f k8s/monitoring/servicemonitor-work-app.yaml
```

Если ваш Prometheus выбирает ServiceMonitors по метке `release` (например, `release: kube-prometheus-stack`), добавьте её в `k8s/monitoring/servicemonitor-work-app.yaml`:

```yaml
metadata:
  labels:
    app: work-app
    release: kube-prometheus-stack
```

Проверка, что цель появилась в Prometheus:

1. Проброс порта Prometheus: `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090`
2. В браузере: http://localhost:9090 → Status → Targets — должна быть цель для `work-app` / `ci-cd-example-app`.
3. В разделе Graph выполните запрос `work_active_requests` — метрика должна отображаться после нескольких запросов к `/work`.

## 3. Установка и настройка prometheus-adapter для HPA

Adapter даёт HPA доступ к кастомным метрикам из Prometheus через Custom Metrics API.

Укажите URL вашего Prometheus в `k8s/monitoring/prometheus-adapter-values.yaml` (по умолчанию — сервис kube-prometheus-stack в namespace `monitoring`). Затем установите adapter:

```bash
helm install prometheus-adapter prometheus-community/prometheus-adapter \
  -n monitoring \
  -f k8s/monitoring/prometheus-adapter-values.yaml
```

В `prometheus-adapter-values.yaml` задано правило, которое прокидывает метрику `work_active_requests` из Prometheus в Custom Metrics API (с привязкой к namespace и pod).

## 4. Проверка доступности метрики через Kubernetes API

Убедитесь, что метрика доступна через Custom Metrics API:

```bash
# Список кастомных метрик
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1" | jq .

# Метрика work_active_requests по namespace
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/*/work_active_requests" | jq .

# Или по конкретному поду (подставьте имя пода приложения)
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/<pod-name>/work_active_requests" | jq .
```

Если в ответе есть `work_active_requests`, HPA может использовать её, например:

### Если Custom Metrics API возвращает ServiceUnavailable

1. **Подождать 1–2 минуты** после установки адаптера — он регистрирует APIService и подключается к Prometheus.

2. **Проверить, что под адаптера запущен:**
   ```bash
   kubectl -n monitoring get pods -l app.kubernetes.io/name=prometheus-adapter
   ```
   Должен быть `Running` и `READY 1/1`. Если под в `CrashLoopBackOff` или не готов — смотреть логи (шаг 3).

3. **Посмотреть логи адаптера** (ошибки подключения к Prometheus, неверный URL и т.п.):
   ```bash
   kubectl -n monitoring logs -l app.kubernetes.io/name=prometheus-adapter --tail=50
   ```

4. **Проверить имя сервиса Prometheus** в namespace `monitoring`:
   ```bash
   kubectl -n monitoring get svc | grep prometheus
   ```
   Нужен сервис с портом **9090** (не node-exporter и не operator). Часто это:
   - `kube-prometheus-stack-prometheus`, или  
   - `kube-prometheus-stack-kube-prometheus-prometheus`.

5. **Если в логах ошибка вида «connection refused» или «no such host»** — в кластере может быть другое имя сервиса. Укажите его в `k8s/monitoring/prometheus-adapter-values.yaml`:
   ```yaml
   prometheus:
     url: http://<имя-сервиса-prometheus>.monitoring.svc
     port: 9090
   ```
   Затем обновите релиз:
   ```bash
   helm upgrade prometheus-adapter prometheus-community/prometheus-adapter \
     -n monitoring -f k8s/monitoring/prometheus-adapter-values.yaml
   ```
   Подождите минуту и снова выполните `kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1"`.

---

Если в ответе есть `work_active_requests`, можно применить манифест HPA (см. раздел «Часть 5» ниже).

---

## Часть 5. Горизонтальное автомасштабирование (HPA)

### 12. Создание HPA

Манифест **`k8s/hpa.yaml`** задаёт HPA с кастомной метрикой `work_active_requests` и поведением масштабирования.

**Параметры (под APP_API_LIMIT=70, APP_API_TIMEOUT=600ms, целевой RPS=280):**
- **minReplicas: 3** — для 280 RPS нужно минимум 3 пода (~117 RPS на под).
- **Целевое значение метрики:** `averageValue: "56"` — масштабирование вверх, когда среднее число активных запросов на под превышает 56 (~80% от лимита 70).
- **Поведение scale-up:** без стабилизационной задержки, за цикл можно добавить до 2 подов или до 100% текущего числа подов.
- **Поведение scale-down:** стабилизация 60 с, не более 1 пода или 33% за 60 с.

**Цикл опроса HPA (1 секунда):** по умолчанию контроллер смотрит метрики каждые 15 с. Чтобы снизить цикл до 1 с, при запуске Minikube укажите (после этого кластер нужно пересоздать):

```bash
minikube delete
minikube start --driver=docker --extra-config=controller-manager.horizontal-pod-autoscaler-sync-period=1s
minikube addons enable ingress
```

После настройки мониторинга и prometheus-adapter примените HPA:

```bash
kubectl apply -f k8s/hpa.yaml
```

Проверка:

```bash
kubectl get hpa ci-cd-example-app
kubectl describe hpa ci-cd-example-app
```

### 13. Проверка работы HPA под нагрузкой

1. **Запустить туннель** (если ещё не запущен), чтобы Ingress был доступен по 80/443:
   ```bash
   minikube tunnel
   ```

2. **В одном терминале смотреть HPA и поды:**
   ```bash
   watch -n 2 'kubectl get hpa ci-cd-example-app; echo; kubectl get pods -l app=ci-cd-example-app'
   ```
   Или по отдельности:
   ```bash
   kubectl get hpa -w
   kubectl get pods -l app=ci-cd-example-app -w
   ```

3. **Создать нагрузку** — см. раздел ниже.

#### Параметры нагрузочного теста для демонстрации масштабирования

Приложение и HPA настроены на: **APP_API_LIMIT=70**, **APP_API_TIMEOUT=600 ms**, целевой RPS=280. Один под даёт ~117 RPS; при 3 подах комфортная нагрузка ~280 RPS, целевое среднее активных запросов на под — 56.

**Перед тестом:** убедитесь, что запущено не меньше 3 подов (HPA с minReplicas: 3 должен их держать). Иначе при одном поде нагрузка 330 RPS сразу даст перегрузку и 429; при масштабировании или перезапуске пода возможен всплеск 502/503 (нет готовых эндпоинтов). Проверка: `kubectl get pods -l app=ci-cd-example-app`. В манифесте Deployment задано `replicas: 3`, чтобы без HPA тоже стартовать с трёх подов.

| Цель | RPS | Длительность | Ожидание |
|------|-----|--------------|----------|
| Базовая нагрузка (без масштабирования) | 200–250 | 60s | 3 пода, метрика ниже 56, REPLICAS не растёт |
| **Показать scale-up** | **380–400** | **120–180s** | Среднее активных на под > 56 → HPA добавляет реплики (4, 5…) |
| Показать scale-down | — | После остановки теста | Через 60 с стабилизации реплики начнут уменьшаться |

**Рекомендуемый сценарий (наглядно показать масштабирование):**

- Запустить нагрузку **380 RPS на 2–3 минуты**. При 3 подах на каждый приходится ~127 RPS → в установившемся режиме ~127×0,6 ≈ **76 активных запросов на под** (выше целевых 56) → HPA увеличит число подов до 4–5.
- Остановить тест и в течение 2–3 минут наблюдать за `kubectl get hpa` и `kubectl get pods`: реплики начнут уменьшаться после окна стабилизации (60 с).

Ingress настроен на host **work.local**. Примеры запуска load-tester (образ собрать: `docker build -f load-tester/Dockerfile -t load-tester ./load-tester`):

```bash
# Через Ingress (обязательно -headers "Host: work.local")
docker run --rm --add-host=host.docker.internal:host-gateway load-tester \
  -url=http://host.docker.internal/work -rps=380 -duration=120s \
  -headers "Host: work.local"
```

Или с хоста, если в /etc/hosts прописан work.local и запущен `minikube tunnel`:

```bash
docker run --rm --add-host=work.local:host-gateway load-tester \
  -url=http://work.local/work -rps=380 -duration=120s
```

Таймаут запроса у load-tester по умолчанию 30s (больше 600 ms обработки), менять не обязательно. Для более длительной демонстрации можно использовать `-rps=380 -duration=180s`.

4. **Ожидаемое поведение:**
   - При нагрузке выше ~280 RPS среднее `work_active_requests` на под превышает 56 → HPA увеличивает число реплик.
   - В выводе `kubectl get hpa` колонка REPLICAS растёт, в `kubectl get pods` появляются новые поды.
   - После окончания нагрузки метрика падает → через 60 с стабилизации HPA уменьшает число реплик.

5. **Равномерное распределение метрики по подам:** каждый под отдаёт свою метрику `work_active_requests` (активные запросы только на этом поде). Ingress распределяет трафик по подам (round-robin), поэтому при равномерной нагрузке значения по подам будут близки. Проверить в Prometheus (запрос `work_active_requests` по лейблу `pod`) или через Custom Metrics API:
   ```bash
   kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/*/work_active_requests" | jq .
   ```

## Краткая шпаргалка

| Действие | Команда |
|----------|--------|
| Установить Prometheus Stack | `helm install kube-prometheus-stack ...` (см. выше) |
| Применить ServiceMonitor | `kubectl apply -f k8s/monitoring/servicemonitor-work-app.yaml` |
| Установить prometheus-adapter | `helm install prometheus-adapter ... -f k8s/monitoring/prometheus-adapter-values.yaml` |
| Применить HPA | `kubectl apply -f k8s/hpa.yaml` |
| Проверить метрику в API | `kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1" \| jq .` |
| Смотреть HPA и поды | `kubectl get hpa; kubectl get pods -l app=ci-cd-example-app` |
| Prometheus UI | `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090` → http://localhost:9090 |

## Метрика work_active_requests

Метрика выставляется приложением (Quarkus + Micrometer) и отражает текущее количество активных запросов к `/work` (обрабатываемых «в процессе»). Имя в Prometheus и в Custom Metrics API: `work_active_requests`.
