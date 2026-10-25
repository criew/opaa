# OPAA Helm-Chart

Betrieb von OPAA unter Kubernetes: das Backend mit einer Instanz oder, unter den Voraussetzungen von
[ADR-0053](../../../docs/decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md), mit mehreren, und das
Frontend. Grundlage ist [ADR-0042](../../../docs/decisions/0042-kubernetes-lieferung-mit-helm.md).

PostgreSQL mit pgvector, Modell-Endpunkte und gegebenenfalls ein Identitätsanbieter werden angebunden,
nicht mitgeliefert.

## Voraussetzungen

- Kubernetes in einer der drei jüngsten gepflegten Minor-Versionen
- Helm 4 oder Helm 3 ab 3.8
- eine erreichbare PostgreSQL-Datenbank mit pgvector 0.8.0 oder neuer; ohne Superuser-Konto legt ein
  Datenbankverwalter vorher Erweiterung und gegebenenfalls Rolle an (`values.yaml`, Abschnitt `database`, und
  Handbuch „Voraussetzungen einer eigenen PostgreSQL“). Zum Ausprobieren gibt es
  `evaluationDatabase.enabled` - nicht für den Betrieb.
- für die Originale ein S3-kompatibler Objektspeicher (empfohlen) oder ein Volume
- ein OpenAI-kompatibler Endpunkt für Einbettung und Chat

## Ausprobieren

Wer OPAA unter Kubernetes erst einmal ausprobieren oder vorführen will, startet den
[Erprobungsaufbau](../../../examples/kubernetes-trial/README.md): Ein Befehl bringt den Chart mit
Keycloak, Ollama, RustFS, Mailpit und der Erprobungsdatenbank in einen leeren lokalen Cluster. Er ist
nicht für den Betrieb gedacht.

## Installation

Die Reihenfolge entspricht dem Handbuch, Kapitel „Kubernetes“, Abschnitt „Installation“
([`docs/handbuch/betrieb/kubernetes.md`](../../../docs/handbuch/betrieb/kubernetes.md#installation)), das jeden Schritt
ausführlich beschreibt.

**1. Namespace anlegen**, mit Pod Security Admission auf `restricted`. Der Chart läuft ohne Ausnahme
darunter. Der Namespace entsteht vor dem Chart, damit die Labels von Anfang an gelten; `helm install`
braucht deshalb kein `--create-namespace`:

```bash
kubectl create namespace opaa
kubectl label namespace opaa \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/warn=restricted
```

**2. Datenbank vorbereiten**, wie unter [Voraussetzungen](#voraussetzungen) beschrieben.

**3. Geheimnisse anlegen.** Für den Betrieb wird ein vorhandenes Secret empfohlen
(`secrets.existingSecret`); welche Schlüssel es enthalten muss, steht in [`values.yaml`](values.yaml).
Alternativ legt der Chart das Secret aus den Werten an. Pflicht sind:

| Wert | Schlüssel im Secret | Inhalt |
|---|---|---|
| `secrets.jwtSecret` | `OPAA_AUTH_JWT_SECRET` | erzeugt mit `openssl rand -base64 48` |
| `secrets.databasePassword` | `OPAA_DB_PASSWORD` | Passwort des Datenbankkontos |
| `secrets.credentialsEncryptionKey` | `OPAA_CREDENTIALS_ENCRYPTION_KEY` | erzeugt mit `openssl rand -base64 32` |
| `secrets.settingsEncryptionKey` | `OPAA_SETTINGS_ENCRYPTION_KEY` | erzeugt mit `openssl rand -base64 32` |
| `secrets.s3AccessKey` | `OPAA_UPLOAD_S3_ACCESS_KEY` | bei `uploads.store: s3`: Zugangsschlüssel des Objektspeichers |
| `secrets.s3SecretKey` | `OPAA_UPLOAD_S3_SECRET_KEY` | bei `uploads.store: s3`: Geheimschlüssel des Objektspeichers |
| `secrets.initialAdminPassword` | `installation-admin-password` | bei `bootstrap.initialAdmin.email`: Passwort des ersten Kontos, als Datei der Antwortdatei eingehängt |

Die Schlüssel erzeugt der Betreiber selbst; der Chart erzeugt keine. Optionale Schlüssel, etwa für
die Modell-Endpunkte, stehen in [`values.yaml`](values.yaml).

**4. Werte-Datei schreiben.** `opaa-werte.yaml` braucht mindestens die Werte aus
[`ci/minimal-values.yaml`](ci/minimal-values.yaml), mit eigenen Geheimnissen. Fehlt ein Pflichtwert,
lehnt `values.schema.json` die Installation ab; `helm template` zeigt das schon vorher (siehe
[Prüfen](#prüfen)).

**5. Installieren.** Jedes Release veröffentlicht den Chart neben den Images in GHCR
([Releases](../../../docs/releases.md)), ab dem ersten Release mit Chart; bis dahin wird aus dem
Repository installiert (unten). Chart-Version und Image-Tags sind dieselbe Versionsnummer:

```bash
helm install opaa oci://ghcr.io/criew/charts/opaa --version X.Y.Z -n opaa -f opaa-werte.yaml
```

Aus dem Repository, etwa um den Stand von `main` zu betreiben:

```bash
helm install opaa deploy/helm/opaa -n opaa -f opaa-werte.yaml \
  --set backend.image.tag=main --set frontend.image.tag=main
```

Zwischen zwei Releases trägt `Chart.yaml` die Platzhalterversion `0.0.0-dev`, zu der es kein Image
gibt. Deshalb nennt die Installation aus dem Repository die Image-Tags ausdrücklich.

**6. Auf das Backend warten und prüfen**, wie unter [Prüfen](#prüfen) beschrieben. Der erste Start
legt das Datenbankschema an; erst danach meldet sich das Backend bereit.

**7. Installation abschließen.** Mit `bootstrap.initialAdmin.email` rendert der Chart die
Antwortdatei der Installation ([ADR-0060](../../../docs/decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md))
aus `bootstrap`, und der erste Start richtet OPAA daraus ein. Ohne sie bleibt OPAA im
Installationsmodus, bis die Installation im Browser abgeschlossen ist; das Token kommt aus
`secrets.installationToken` oder steht einmalig im Protokoll. Die Ausgabe von `helm install` nennt
den Weg.

## Was der Chart anbindet

| Werte | Wofür |
|---|---|
| `database` | externe PostgreSQL, mit `sslMode` und eigenem `schema` |
| `evaluationDatabase` | PostgreSQL im Release, **nur zur Erprobung** |
| `uploads` | Originalablage im Objektspeicher (`s3`, Vorgabe) oder auf einem Volume (`persistentVolumeClaim`) |
| `filesystemSources` | Verzeichnisse für den Dateisystem-Konnektor, nur lesend eingehängt |
| `extraCACertificates` | eigene CA-Zertifikate für Identitätsanbieter, Modelle, Objektspeicher, Datenbank und Quellen (etwa Confluence, Nextcloud, Webverzeichnis und Feed) |
| `embedding`, `rerank` | Modell-Endpunkte, auch im Cluster (etwa Ollama über seinen Service) |
| `mail` | Zeitgrenzen der SMTP-Verbindung; den Mailserver richtet die Oberfläche ein |
| `targetValidation` | Ausnahmen der Adressprüfung für Quellen und Identitätsanbieter im eigenen Netz |

## Was der Chart festlegt

- **Eine Instanz, Strategie `Recreate`, als Vorgabe.** Ein Upgrade unterbricht den Betrieb kurz. Die
  Schemamigration läuft beim Start des Backends, die Startup-Probe gibt ihr bis zu zehn Minuten.
  Mehrere Instanzen beschreibt [Mehrere Backend-Instanzen](#mehrere-backend-instanzen).
- **Pod Security Standard `restricted`.** Alle Pods laufen ohne Root, mit nur lesbarem
  Root-Dateisystem und ohne Token des ServiceAccounts.
- **Betriebsmodus `oidc`.** Ohne Identitätsanbieter melden sich Personen lokal an
  ([ADR-0033](../../../docs/decisions/0033-lokale-benutzerverwaltung.md)).
- **Die Antwortdatei wirkt nur bis zum Abschluss der Installation.** `bootstrap.*` wird zur
  Antwortdatei; Passwort und Schlüssel kommen als Dateien aus dem Secret, nie als Variable. Danach
  führt die Oberfläche.

## Mehrere Backend-Instanzen

`backend.replicas` größer 1 rendert nur mit Migrations-Job und einer Originalablage, die jeder Pod
sieht; sonst bricht `helm template` mit einer Meldung ab:

```yaml
backend:
  replicas: 2
  migration:
    enabled: true
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: kubernetes.io/hostname
      whenUnsatisfiable: ScheduleAnyway
uploads:
  store: s3            # oder persistentVolumeClaim mit accessModes: [ReadWriteMany]
```

Der Chart setzt dann:

- **Migrations-Job** `<Release>-migrate-<Prüfsumme>` mit `OPAA_MODE=migrate`, neu bei jedem Upgrade
  mit neuem Image oder neuer Konfiguration. Die Backend-Pods laufen mit
  `SPRING_FLYWAY_ENABLED=false` und warten in einem Init-Container (`OPAA_MODE=await-migration`),
  bis das Schema ihrer Version vollständig ist. Mit `networkPolicy.egress.enabled` bekommt der Job
  eine eigene Ausgangs-Policy, die Erprobungsdatenbank lässt ihn ein.
- **Strategie `RollingUpdate`** mit `maxUnavailable: 0` und `maxSurge: 1`; `backend.updateStrategy:
  Recreate` bleibt wählbar und ist der Weg, einen Wert des Konfigurationsabdrucks zu ändern.
- **PodDisruptionBudget** mit `maxUnavailable: 1` ab zwei Replikaten.
- **Zeiten:** `lifecycle.preStop.sleep` über `backend.preStopSleepSeconds`;
  `backend.shutdownTimeoutSeconds` folgt dem Lese-Timeout des Frontends, die Stopp-Frist liegt
  darüber.
- **Verbindungen:** `backend.databasePoolSize` je Pod; `values.yaml` nennt die Grenze für
  `max_connections`.

Rate-Limits, Kontingente und Thread-Pools gelten je Pod. `backend.installationBudgets` nimmt je
Variable das Budget der ganzen Installation und setzt jedem Pod seinen Teil. Das Handbuch, Kapitel
„Kubernetes“, beschreibt Ablauf, Rückweg und Alarme je Pod.

## Netz und Client-Adresse

- **Eingang zum Backend:** Standardmäßig lässt eine NetworkPolicy nur die Frontend-Pods zu, dazu
  `networkPolicy.backendIngress.extraFrom`.
- **Eingang zum Frontend:** Nur der Ingress- oder Gateway-Controller und der Pod von `helm test`,
  sobald `networkPolicy.frontendIngress.controllerNamespaceSelector` und `controllerPodSelector`
  Labels nennen.
- **Ausgang:** `networkPolicy.egress.enabled` begrenzt den Ausgang von Backend und Frontend auf DNS,
  die Pods des Releases und `networkPolicy.egress.backendRules`. Das ist empfohlen, sobald
  Datenbank, Identitätsanbieter, Modelle und Quellen eingetragen sind.
- **Client-Adresse:** `trustedProxyCidrs` nennt das Pod-Netz, damit Rate-Limits, Anmeldesperre und
  `localAdminAllowedCidrs` die Adresse der Person sehen statt der des Frontend-Pods. Das Rendern
  bricht ab, solange nicht beide Eingangs-Policies an sind. Peers aus
  `networkPolicy.backendIngress.extraFrom` gelten dann als vertrauenswürdige Proxys und brauchen
  einen `podSelector`. Die Netzwerkschicht des Clusters muss
  NetworkPolicies durchsetzen, das kann der Chart nicht prüfen.
- **Actuator:** Von außen ist kein `/actuator`-Pfad erreichbar; der Frontend-nginx reicht nur `/api/`
  und `/mcp` weiter. Prometheus fragt das Backend direkt ab.
- **CSP:** Liegt der Issuer des Identitätsanbieters (`bootstrap.oidc.issuerUri`) auf einem anderen
  Origin als `publicBaseUrl`, gehört dieser in `frontend.cspConnectSrcExtra`, ebenso der Origin des
  `token_endpoint` aus seinem Discovery-Dokument, wenn er abweicht (Google:
  `https://oauth2.googleapis.com`). Erlaubt die Policy den Issuer nicht, warnt `helm install` in
  seinen Hinweisen; den `token_endpoint` kann Helm nicht prüfen.

## Betriebsüberwachung

Proben und Metriken laufen über den Management-Port (`backend.managementPort`), getrennt von der API.
`metrics.serviceMonitor` legt einen ServiceMonitor an, `metrics.prometheusRule` Grundalarme, und
`backend.logFormat` schaltet JSON-Protokolle ein. Mit kube-prometheus-stack:

```yaml
metrics:
  serviceMonitor:
    enabled: true
    labels:
      release: kube-prometheus-stack
  prometheusRule:
    enabled: true
    labels:
      release: kube-prometheus-stack
```

Metriken und Alarme beschreibt das Handbuch im Kapitel „Kubernetes“.

## Prüfen

```bash
# vor der Installation
helm template opaa deploy/helm/opaa -n opaa -f opaa-werte.yaml > /dev/null
# nach der Installation
kubectl -n opaa rollout status deploy/opaa-backend --timeout=15m
helm test opaa -n opaa
```

`helm template` bricht ab, wenn das Schema oder eine Regel des Charts die Werte ablehnt, etwa
`backend.replicas: 2` ohne Migrations-Job. `helm lint` eignet sich dafür nicht: Es meldet die Regeln des Charts nur als
`[INFO]` und endet trotzdem erfolgreich.

`helm test` ruft `/api/health` über den Frontend-Service ab und prüft damit die Kette
Frontend-nginx → Backend.

Die CI (`.github/workflows/helm-chart.yml`) rendert den Chart mit jedem Wertesatz unter
[`ci/`](ci/), prüft die Manifeste mit Helm 3.8 und Helm 4 gegen die unterstützten
Kubernetes-Versionen, führt die Unit-Tests der Alarme
([`ci/prometheusrule-test.yaml`](ci/prometheusrule-test.yaml)) mit promtool aus, packt ihn mit
Beispiel-Tags wie der Release-Lauf und installiert ihn auf kind. Die Skripte dazu liegen unter
[`deploy/helm/ci/`](../ci/).
