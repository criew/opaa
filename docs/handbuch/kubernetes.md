# Kubernetes

> **Entwurf.** Dieses Kapitel beschreibt den Betrieb mit dem Helm-Chart. Bisher enthält es die
> Betriebsüberwachung; Installation, Werte, Aktualisierung und Sicherung folgen.

## Betriebsüberwachung

OPAA bindet sich an die Überwachung des Clusters an und bringt keine eigene mit. Das Backend liefert
Metriken im Prometheus-Format, der Chart beschreibt sie für den Prometheus Operator und bringt einige
Grundalarme mit. Die Protokolle gehen wie bei jedem Pod auf die Standardausgabe.

### Management-Port

Proben und Metriken laufen über einen eigenen Port des Backends, den **Management-Port**. Die API
bleibt auf ihrem Port. Damit kann die NetworkPolicy des Backends dem Prometheus den Management-Port
öffnen, ohne ihm die API zu öffnen. Wer die API direkt erreicht, könnte sonst die Client-Adresse
fälschen, aus der Rate-Limits und Anmeldesperre zählen.

Von außen ist kein Actuator-Pfad erreichbar: Der Frontend-nginx reicht nur `/api/` und `/mcp` an das
Backend weiter.

### Metriken einsammeln

| Wert | Vorgabe | Wirkung |
|---|---|---|
| `backend.managementPort` | `8081` | Port von Proben und Metriken (`MANAGEMENT_SERVER_PORT`) |
| `metrics.serviceMonitor.enabled` | `false` | legt einen `ServiceMonitor` an; Prometheus fragt `/actuator/prometheus` auf dem Management-Port ab |
| `metrics.serviceMonitor.labels` | leer | Labels, nach denen der Prometheus seine ServiceMonitors auswählt |
| `metrics.serviceMonitor.interval`, `scrapeTimeout` | `30s`, `10s` | Abfrageintervall und Zeitgrenze |
| `metrics.podAnnotations` | `false` | setzt `prometheus.io/scrape`, `port` und `path` am Backend-Pod, für Prometheus ohne Operator |
| `metrics.scrapeFrom` | Pods mit `app.kubernetes.io/name: prometheus` in jedem Namespace | wer den Management-Port erreichen darf, solange Metriken eingeschaltet sind |

Mit **kube-prometheus-stack** genügen zwei Werte. Der Prometheus dieses Charts wählt ServiceMonitors
nach dem Label `release` mit dem Namen seines Releases aus:

```yaml
metrics:
  serviceMonitor:
    enabled: true
    labels:
      release: kube-prometheus-stack
```

Seine Pods tragen `app.kubernetes.io/name: prometheus`, die Vorgabe von `metrics.scrapeFrom` lässt
sie also durch. Ist der Prometheus anders beschriftet, gehört sein Selektor nach `metrics.scrapeFrom`.
Fehlen dort die passenden Labels, sieht man das am Alarm `OpaaBackendMetricsMissing`.

Die wichtigsten Metriken:

| Metrik | Bedeutung |
|---|---|
| `up` | ob Prometheus das Backend erreicht |
| `http_server_requests_seconds_*` | Anfragen an das Backend nach Pfad, Methode und Status |
| `opaa_query_duration_seconds_*`, `opaa_query_count_total` | Dauer und Anzahl der Fragen im Chat |
| `opaa_chat_search_duration_seconds_*` | Dauer der Suche über Chats |
| `opaa_indexing_documents_total` | verarbeitete Dokumente der Indexierung, nach Ergebnis |
| `opaa_auth_local_account_lockout_total` | Sperren lokaler Konten nach Fehlversuchen |
| `executor_active_threads{name="indexingTaskExecutor"}` | laufende Indexierungsläufe |
| `hikaricp_connections_*` | Verbindungspool zur Datenbank |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | Speicher der JVM |

### Alarme

`metrics.prometheusRule.enabled` legt eine `PrometheusRule` mit fünf Alarmen an.
`metrics.prometheusRule.labels` trägt die Labels, nach denen der Prometheus Regeln auswählt; bei
kube-prometheus-stack wieder `release: <Release-Name>`.

| Alarm | Schwere | Löst aus, wenn | Erste Prüfung |
|---|---|---|---|
| `OpaaBackendNotReady` | critical | das Backend zehn Minuten lang weniger bereite Pods hat als gewünscht | Protokoll des Pods; ist die Datenbank erreichbar? |
| `OpaaBackendMetricsMissing` | warning | Prometheus das Backend zehn Minuten lang nicht abfragen kann | Pod, `ServiceMonitor`, `metrics.scrapeFrom` |
| `OpaaHighErrorRate` | warning | der Anteil der Antworten mit Status 5xx zehn Minuten lang über der Schwelle liegt | Protokoll; oft ein nicht erreichbares Modell |
| `OpaaIndexingStalled` | warning | ein Indexierungslauf aktiv ist, aber in der Frist kein Dokument verarbeitet hat | Laufstatus der Bibliothek, Quelle, Embedding-Modell |
| `OpaaHeapUsageHigh` | warning | der Heap 15 Minuten lang über der Schwelle belegt ist | Speichergrenze oder Heap-Anteil anheben |

| Wert | Vorgabe | Wirkung |
|---|---|---|
| `metrics.prometheusRule.errorRateThreshold` | `0.05` | Schwelle von `OpaaHighErrorRate` (Anteil) |
| `metrics.prometheusRule.heapUsageThreshold` | `0.9` | Schwelle von `OpaaHeapUsageHigh` (Anteil des maximalen Heaps) |
| `metrics.prometheusRule.indexingStallMinutes` | `60` | Frist ohne Fortschritt für `OpaaIndexingStalled` |

`OpaaBackendNotReady` liest die Metriken von **kube-state-metrics**, das kube-prometheus-stack
mitbringt. Ist das Backend mit `backend.replicas: 0` bewusst angehalten, schweigt der Alarm.

### Protokolle

Das Backend schreibt auf die Standardausgabe; ein Log-Sammler des Clusters nimmt sie dort ab. Mit
`backend.logFormat` wird jede Zeile ein JSON-Objekt, das der Sammler ohne eigenes Muster zerlegt:

| `backend.logFormat` | Format |
|---|---|
| leer (Vorgabe) | Text, wie im Compose-Betrieb |
| `ecs` | Elastic Common Schema |
| `logstash` | Logstash-JSON |
| `gelf` | Graylog Extended Log Format |

Mehrzeilige Ausgaben wie das Einmalpasswort des Erststarts bleiben dabei ein einziges Ereignis.
