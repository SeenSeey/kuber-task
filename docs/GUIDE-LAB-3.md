## 1. Запуск кластера Kubernetes

# При наличии старого кластера — удалить
minikube delete

# Запуск с нужными параметрами
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
minikube start --driver=docker \
  --extra-config=controller-manager.horizontal-pod-autoscaler-sync-period=1s \
  --addons=ingress \
  --ports=80:80,443:443,9090:9090
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка Ingress:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl get pods -n ingress-nginx
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Доступ к приложению и Ingress — по **http://127.0.0.1** (порты 80/443 проброшены на хост). На macOS с драйвером Docker доступ по `minikube ip` к портам 80/443 часто недоступен; используйте **127.0.0.1** или добавьте в `/etc/hosts` строку `127.0.0.1 work.local` и открывайте **http://work.local**.

---

## 2. Сборка образа и загрузка в кластер
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
docker build -t ci-cd-example-app:latest .
minikube image load ci-cd-example-app:latest
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

## 3. Применение конфигураций приложения

Из корня репозитория (где лежит каталог `k8s/`):
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl apply -f k8s/
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl get pods -l app=ci-cd-example-app
kubectl get svc ci-cd-example-app
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка ответа приложения (доступ без туннеля по localhost):
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
curl http://127.0.0.1/work/status
# или, если добавлен work.local в /etc/hosts:
curl http://work.local/work/status
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

## 4. Настройка мониторинга Prometheus
### 4.1. Установка kube-prometheus-stack

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

kubectl create namespace monitoring

helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f k8s/monitoring/kube-prometheus-stack-values.yaml
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Дождаться готовности подов:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl -n monitoring get pods -w
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

### 4.2. ServiceMonitor для приложения

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl apply -f k8s/monitoring/servicemonitor-work-app.yaml
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка в Prometheus: если в `kube-prometheus-stack-values.yaml` для Prometheus задан `nodePort: 9090`, то при `--ports=9090:9090` UI доступен по **http://127.0.0.1:9090**. Если там указан другой порт (например 30090), используйте port-forward:

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

В браузере откройте **http://127.0.0.1:9090** → Status → Targets — должна быть цель для приложения; в Graph — запрос `work_active_requests`.

### 4.3. prometheus-adapter для HPA

Нужен для того, чтобы HPA использовал кастомную метрику из Prometheus:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
helm upgrade --install prometheus-adapter prometheus-community/prometheus-adapter \
  -n monitoring \
  -f k8s/monitoring/prometheus-adapter-values.yaml
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка доступности метрики через Custom Metrics API (подождите 1–2 минуты после установки адаптера):

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1" | jq .
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/*/work_active_requests" | jq .
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Если метрика есть — можно применять HPA.

## 5. Настройка HPA

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl apply -f k8s/hpa.yaml
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl get hpa ci-cd-example-app
kubectl describe hpa ci-cd-example-app
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

HPA использует метрику `work_active_requests` и целевое среднее 56 на под; приложение настроено на лимит 70 одновременных запросов и таймаут 600 ms.

## 6. Нагрузочное тестирование
### Сборка load-tester

Из корня репозитория:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
docker build -f load-tester/Dockerfile -t load-tester ./load-tester
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

### Запуск нагрузки (без minikube tunnel)

Доступ к приложению по **http://127.0.0.1** (порты уже проброшены при `minikube start`).
С хоста:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
docker run --rm --add-host=host.docker.internal:host-gateway load-tester \
  -url=http://host.docker.internal/work \
  -rps=380 \
  -duration=120s
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Если Ingress ожидает Host (например, `work.local`), добавьте заголовок:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
docker run --rm --add-host=host.docker.internal:host-gateway load-tester \
  -url=http://host.docker.internal/work \
  -rps=380 \
  -duration=120s \
  -headers "Host: work.local"
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Либо с хоста, если в `/etc/hosts` есть `127.0.0.1 work.local`:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
./load-tester -url=http://127.0.0.1/work -rps=380 -duration=120s
# или
./load-tester -url=http://work.local/work -rps=380 -duration=120s
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

### Наблюдение за HPA и подами

В отдельном терминале:
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
watch -n 2 'kubectl get hpa ci-cd-example-app; echo; kubectl get pods -l app=ci-cd-example-app'
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Ожидание: при ~380 RPS среднее `work_active_requests` на под превысит 56 → HPA начнёт добавлять реплики; после остановки теста через 60 с стабилизации реплики начнут уменьшаться.

---

## 7. Настройка Grafana

### 7.1. Установка Grafana с постоянным хранилищем

В репозитории уже есть `k8s/monitoring/grafana-values.yaml` с настройками:
- **persistence.enabled: true** — PVC 5Gi (`storageClassName: standard`) для сохранения дашбордов и настроек.
- **service.type: ClusterIP**, порт 80.

Установка (в namespace `monitoring`):

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

helm upgrade --install grafana grafana/grafana \
  -n monitoring \
  -f k8s/monitoring/grafana-values.yaml
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Проверка:

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
kubectl -n monitoring get pods -l app.kubernetes.io/name=grafana
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Под должен быть в состоянии `Running`, PVC создан.

### 7.2. Доступ к Grafana

2. При запуске Minikube с `--addons=ingress` и `--ports=80:80` Ingress-доступ к кластеру уже проброшен на хост. Откройте в браузере:

   - **http://grafana.work.local**

3. Альтернатива без Ingress (или если Ingress не работает) — port-forward:

   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   kubectl -n monitoring port-forward svc/grafana 3000:80
   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   Тогда доступ по **http://127.0.0.1:3000**.

### 7.3. Логин и источник данных Prometheus

- Логин: **admin**.
- Пароль: из секрета Helm-релиза:

  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  kubectl -n monitoring get secret grafana -o jsonpath="{.data.admin-password}" | base64 -d
  echo
  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

В `grafana-values.yaml` уже преднастроен datasource Prometheus:
- URL: `http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090`
- `isDefault: true`.

После входа в UI проверьте:
- **Connections → Data sources → Prometheus** → **Save & test** — статус «Data source is working».
- Дашборды и настройки будут сохраняться в PVC (постоянное хранилище).

---

## Краткая шпаргалка команд

| Действие | Команда |
|----------|--------|
| Запуск кластера | `minikube start --driver=docker --extra-config=controller-manager.horizontal-pod-autoscaler-sync-period=1s --addons=ingress --ports=80:80,443:443,9090:9090` |
| Сборка образа в Minikube | `eval $(minikube docker-env)` → `docker build -t ci-cd-example-app:latest .` |
| Применить манифесты | `kubectl apply -f k8s/` |
| Установить Prometheus Stack | `helm upgrade --install kube-prometheus-stack ... -f k8s/monitoring/kube-prometheus-stack-values.yaml` |
| ServiceMonitor приложения | `kubectl apply -f k8s/monitoring/servicemonitor-work-app.yaml` |
| Установить prometheus-adapter | `helm upgrade --install prometheus-adapter ... -f k8s/monitoring/prometheus-adapter-values.yaml` |
| Применить HPA | `kubectl apply -f k8s/hpa.yaml` |
| Проверка приложения | `curl http://127.0.0.1/work/status` |
| Prometheus UI | http://127.0.0.1:9090 (если порт проброшен при start) или `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090` |
| Пересборка и перезапуск | `eval $(minikube docker-env)` → `docker build -t ci-cd-example-app:latest .` → `kubectl rollout restart deployment/ci-cd-example-app` |


