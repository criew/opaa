# OPAA Helm-Chart

Betrieb von OPAA unter Kubernetes: genau eine Backend-Instanz ([ADR-0021](../../../docs/decisions/0021-single-instance-betrieb.md))
und das Frontend. Grundlage ist [ADR-0042](../../../docs/decisions/0042-kubernetes-lieferung-mit-helm.md).

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

## Installation

Jedes Release veröffentlicht den Chart neben den Images in GHCR ([Releases](../../../docs/releases.md)),
ab dem ersten Release mit Chart; bis dahin wird aus dem Repository installiert (unten). Chart-Version
und Image-Tags sind dieselbe Versionsnummer:

```bash
helm install opaa oci://ghcr.io/criew/charts/opaa --version X.Y.Z \
  -n opaa --create-namespace -f meine-werte.yaml
```

Aus dem Repository, etwa um den Stand von `main` zu betreiben:

```bash
helm install opaa deploy/helm/opaa -n opaa --create-namespace -f meine-werte.yaml \
  --set backend.image.tag=main --set frontend.image.tag=main
```

Zwischen zwei Releases trägt `Chart.yaml` die Platzhalterversion `0.0.0-dev`, zu der es kein Image
gibt. Deshalb nennt die Installation aus dem Repository die Image-Tags ausdrücklich.

`meine-werte.yaml` braucht mindestens die Werte aus [`ci/minimal-values.yaml`](ci/minimal-values.yaml),
mit eigenen Geheimnissen. Fehlt ein Pflichtwert, lehnt `values.schema.json` die Installation ab.

Die Schlüssel erzeugt der Betreiber selbst:

| Wert | Erzeugen mit |
|---|---|
| `secrets.jwtSecret` | `openssl rand -base64 48` |
| `secrets.credentialsEncryptionKey` | `openssl rand -base64 32` |
| `secrets.settingsEncryptionKey` | `openssl rand -base64 32` |

Für den Betrieb wird statt der Werte ein vorhandenes Secret empfohlen (`secrets.existingSecret`). Welche
Schlüssel es enthalten muss, steht in [`values.yaml`](values.yaml).

## Was der Chart anbindet

| Werte | Wofür |
|---|---|
| `database` | externe PostgreSQL, mit `sslMode` und eigenem `schema` |
| `evaluationDatabase` | PostgreSQL im Release, **nur zur Erprobung** |
| `uploads` | Originalablage im Objektspeicher (`s3`, Vorgabe) oder auf einem Volume (`persistentVolumeClaim`) |
| `filesystemSources` | Verzeichnisse für den Dateisystem-Konnektor, nur lesend eingehängt |
| `extraCACertificates` | eigene CA-Zertifikate für Identitätsanbieter, Modelle, Objektspeicher und Datenbank |
| `embedding`, `rerank` | Modell-Endpunkte, auch im Cluster (etwa Ollama über seinen Service) |
| `mail` | Zeitgrenzen der SMTP-Verbindung; den Mailserver richtet die Oberfläche ein |
| `targetValidation` | Ausnahmen der Adressprüfung für Quellen und Identitätsanbieter im eigenen Netz |

## Was der Chart festlegt

- **Eine Instanz, Strategie `Recreate`.** Ein Upgrade unterbricht den Betrieb kurz. Die
  Schemamigration läuft beim Start des Backends, die Startup-Probe gibt ihr bis zu zehn Minuten.
- **Pod Security Standard `restricted`.** Alle Pods laufen ohne Root, mit nur lesbarem
  Root-Dateisystem und ohne Token des ServiceAccounts.
- **Betriebsmodus `oidc`.** Ohne Identitätsanbieter melden sich Personen lokal an
  ([ADR-0033](../../../docs/decisions/0033-lokale-benutzerverwaltung.md)).
- **Startwerte wirken nur beim ersten Start.** `bootstrap.*` wird einmal in die Datenbank übernommen,
  danach führt die Oberfläche.

## Netz und Client-Adresse

Der Namespace sollte Pod Security Admission auf `restricted` setzen; der Chart läuft ohne Ausnahme
darunter:

```bash
kubectl label namespace opaa pod-security.kubernetes.io/enforce=restricted
```

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
- **CSP:** Liegt die OIDC-Authority auf einem anderen Origin als `publicBaseUrl`, gehört dieser in
  `frontend.cspConnectSrcExtra`.

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
helm lint deploy/helm/opaa -f deploy/helm/opaa/ci/minimal-values.yaml
helm test opaa -n opaa
```

`helm test` ruft `/api/health` über den Frontend-Service ab und prüft damit die Kette
Frontend-nginx → Backend.

Die CI (`.github/workflows/helm-chart.yml`) rendert den Chart mit jedem Wertesatz unter
[`ci/`](ci/), prüft die Manifeste mit Helm 3.8 und Helm 4 gegen die unterstützten
Kubernetes-Versionen, führt die Unit-Tests der Alarme
([`ci/prometheusrule-test.yaml`](ci/prometheusrule-test.yaml)) mit promtool aus, packt ihn mit
Beispiel-Tags wie der Release-Lauf und installiert ihn auf kind. Die Skripte dazu liegen unter
[`deploy/helm/ci/`](../ci/).
