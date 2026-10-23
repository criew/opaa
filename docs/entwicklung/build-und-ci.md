# Build und CI

Nachschlagewerk zu Befehlen außerhalb des täglichen Builds. Die Grundbefehle (Build, Test, Formatierung,
Start von Backend und Frontend) und die Pre-Push-Checkliste stehen in [AGENTS.md](../../AGENTS.md#build--test).

## Frontend lokal

```bash
# aus frontend/
VITE_ENABLE_MOCKS=true pnpm run dev     # Dev-Server mit MSW-Mocks
# Im dev-Auth-Modus laufen Anfragen als "dev-admin"; auf einen regulären Nutzer
# wechseln mit http://localhost:5173/?devUser=dev-user
```

## Integrationstest-Suiten

Nicht Teil von `./gradlew build` und `./gradlew test`.

```bash
# aus backend/
./gradlew openAiIntegrationTest   # OpenAI-E2E-Tests (io.opaa.integration.*); braucht
                                  # OPAA_OPENAI_API_KEY und Docker, nicht Teil von build/test
OPAA_CONFLUENCE_IT=true ./gradlew confluenceIntegrationTest
                                  # Zugriffsschicht gegen ein echtes Confluence Data
                                  # Center im Container (io.opaa.integration.confluence.*);
                                  # braucht Docker, ~4 GB RAM und Internet für die 3-Stunden-
                                  # Testlizenz; nicht Teil von build/test, in CI nightly und per
                                  # Label "confluence-suite" (ADR-0023, #1171)
./gradlew keycloakIntegrationTest # Verzeichnis-Konnektor gegen ein echtes Keycloak im
                                  # Container (io.opaa.integration.keycloak.*); braucht nur Docker,
                                  # keine Lizenz und kein Internet, deshalb auch keine
                                  # Umgebungsvariable. Nicht Teil von build/test: gemessen ~46 s
                                  # Startzeit und ~880 MiB allein für den Container (dazu Postgres
                                  # und die Test-JVM). In CI nightly und per Label
                                  # "keycloak-suite" (#1817, ADR-0036 Entscheidung 3)
./gradlew nextcloudIntegrationTest
                                  # Nextcloud-Konnektor und FileStoreFolderContract gegen das
                                  # offizielle Nextcloud-Image (io.opaa.integration.nextcloud.*);
                                  # braucht Docker, Group Folders zusätzlich den App-Store. Nicht
                                  # Teil von build/test: Image rund 2,1 GB. In CI nightly und per
                                  # Label "nextcloud-suite" (#2152)
OPAA_GDRIVE_IT_KEY_FILE=… OPAA_GDRIVE_IT_FOLDER_ID=… ./gradlew googleDriveIntegrationTest
                                  # Google-Drive-Konnektor gegen ein echtes Workspace, nur
                                  # lesend; läuft nur lokal beim Maintainer, Schlüsseldatei
                                  # außerhalb des Repos, kein CI-Job und kein Secret. Einrichtung:
                                  # README.md im Testpaket indexing.source.googledrive (ADR-0040)
```

## Helm-Chart

Der Chart liegt unter `deploy/helm/opaa/` ([ADR-0042](../decisions/0042-kubernetes-lieferung-mit-helm.md)).

```bash
# Helm-Chart (aus deploy/helm/opaa/, ADR-0042) — die Vorgabewerte allein sind bewusst
# unvollständig, geprüft wird mit den Wertedateien unter ci/
helm template opaa . -f ci/minimal-values.yaml   # bricht bei Abbruchregeln ab; helm lint meldet
                                                # sie nur als INFO und endet mit Exit 0
helm template opaa . -f ci/full-values.yaml
helm template opaa . -f ci/multi-values.yaml    # zwei Backend-Pods mit Migrations-Job (ADR-0053)

# Chart-CI (aus dem Repository-Wurzelverzeichnis; Workflow .github/workflows/helm-chart.yml)
deploy/helm/ci/static-check.sh          # helm lint + template mit jedem Wertesatz unter
                                        # deploy/helm/opaa/ci/*-values.yaml, kubeconform gegen die
                                        # drei unterstützten Kubernetes-Versionen, Prüfung der
                                        # Abbruchregeln und Trockenlauf der Release-Skripte;
                                        # braucht helm, kubeconform, curl und jq. Neue Wertesätze dort
                                        # ablegen, sie werden automatisch geprüft
deploy/helm/ci/package-chart.sh v1.2.3 <ziel>
                                        # Chart packen wie der Release-Lauf (publish-images.yml):
                                        # Version und appVersion aus dem Tag, prüft die Image-Tags
                                        # der Vorgabewerte; veröffentlicht nichts. Das Tag-Format
                                        # steht allein in release-version.sh, die Existenzprüfung
                                        # von Images und Chart in GHCR in oci-published.sh (nur
                                        # HTTP 404 gilt als frei), der Inhaltsvergleich des Charts
                                        # vor der Attestierung in chart-matches.sh
deploy/helm/ci/install-test.sh          # Installationstest: ct install mit der Erprobungsdatenbank
                                        # des Charts und eingeschalteten NetworkPolicies in einen
                                        # Namespace mit Pod Security Standard restricted; wartet auf
                                        # Datenbank, Backend und Frontend und führt helm test aus,
                                        # ohne LLM. Braucht kubectl, helm, ct und
                                        # einen kind-Cluster mit den Images aus dem Commit:
                                        #   kind create cluster
                                        #   docker build -f backend/Dockerfile -t opaa-ci/backend:ci .
                                        #   docker build -f frontend/Dockerfile -t opaa-ci/frontend:ci .
                                        #   kind load docker-image opaa-ci/backend:ci opaa-ci/frontend:ci
                                        # In CI bei Änderungen am Chart und an den Image-Dateien,
                                        # nightly und per Label "helm-suite"
deploy/helm/ci/restore-test.sh          # Wiederherstellungsprobe (docs/handbuch/betrieb.md,
                                        # Abschnitt „Wiederherstellungsprobe“): Quelle installieren und über
                                        # die API füllen, mit den Befehlen des Handbuchs sichern, in
                                        # einen zweiten Namespace zurückspielen, P1-P4, P6, P9-P12
                                        # prüfen; Ergebnis, Zeiten und Größen in der Job-
                                        # Zusammenfassung. Braucht kubectl, helm, curl, jq, openssl,
                                        # die GNU-Werkzeuge date (-d), sha256sum, comm und paste und
                                        # denselben kind-Cluster wie install-test.sh; läuft in CI im
                                        # selben Job direkt danach. Löscht die Namespaces
                                        # opaa-probe-quelle und opaa-probe-ziel ohne Rückfrage, vor
                                        # dem Lauf und nach einem erfolgreichen; ein fehlgeschlagener
                                        # Lauf und KEEP_NAMESPACES=1 lassen sie zur Fehlersuche stehen
deploy/helm/ci/rule-test.sh             # Unit-Tests der Alarmregeln: rendert die PrometheusRule
                                        # und führt deploy/helm/opaa/ci/prometheusrule-test.yaml
                                        # mit promtool aus; braucht helm, yq und promtool. In CI
                                        # Teil der statischen Prüfung
```

## Kubernetes-Erprobungsaufbau

```bash
# Kubernetes-Erprobungsaufbau (aus dem Wurzelverzeichnis, siehe examples/kubernetes-trial/README.md):
# OPAA-Chart mit Keycloak, Ollama, RustFS, Mailpit und Erprobungsdatenbank im aktuellen
# kubectl-Kontext; nicht für den Betrieb, nicht Teil der CI
k3d cluster create opaa-trial -p "127.0.0.1:8088:80@loadbalancer"
examples/kubernetes-trial/trial.sh up   # installieren oder aktualisieren, danach http://opaa.localhost:8088
examples/kubernetes-trial/trial.sh down # beide Namespaces samt Daten entfernen
```

## E2E-Suite

```bash
# E2E-Suite (aus e2e/, siehe e2e/README.md)
pnpm install                            # Abhängigkeiten installieren
pnpm exec playwright install --with-deps chromium   # Browser installieren (einmalig)
pnpm test                               # Stack via Docker Compose starten, Suite ausführen, Stack wieder stoppen
```

## Zwei-Instanzen-Suite

Die Szenarien unter `e2e/multi-instance/` belegen den Mehrinstanzbetrieb
([ADR-0053](../decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md)) mit zwei Backend-Instanzen gegen eine
Datenbank, unter Docker Compose und auf kind. Sie sprechen die API beider Instanzen direkt an und
öffnen keinen Browser.

| Szenario | Prüft |
|---|---|
| `01-kaltstart` | gleichzeitiger Kaltstart nach dem Migrationsschritt gegen eine leere Datenbank: beide Instanzen im Register, jede Startaktion einmal, eine dritte Instanz mit abweichender Konfiguration startet nicht |
| `02-wirkung-ueber-instanzen` | Freigabe, Gruppenmitgliedschaft, Abmelden, Sperren, Abschalten der lokalen Anmeldung und ein neuer Anmeldeanbieter wirken sofort auf der anderen Instanz; Statusseite auf beiden gleich |
| `03-genau-einmal` | jede Mail der Outbox geht genau einmal hinaus; ein geplanter Lauf startet je Fälligkeit einmal |
| `04-neustart-der-anderen-instanz` | Indexierungslauf und Upload von A überstehen den Neustart von B (ai-stub hält die Einbettungen von A bis dahin zurück) |
| `05-rolling-update` | Update von n−1 auf n in der Reihenfolge des Handbuchs unter Last: keine Fehlerantwort, jede Mail der Outbox genau einmal; auf kind danach `kubectl rollout restart` unter Last |
| `06-pool-unter-last` | mehr gleichzeitige Anfragen als Pool-Verbindungen, darunter gezählte Anmeldungen und neue Konten: kein Fehler, keine Instanz über Pool + 1 Verbindungen |

Zwei Phasen, je mit leerer Datenbank: `fresh` startet die aktuelle Version (01–04, 06), `rolling`
startet die Vorgängerversion und lässt Szenario 05 auf die aktuelle aktualisieren.

**Vorgängerversion n−1** (`e2e/scripts/previous-version.mjs`):

1. `E2E_MULTI_PREVIOUS_REF` bzw. die Eingabe `previous_ref` des Workflows, wenn gesetzt;
2. sonst das Release `vX`, wenn `backend/src/main/resources/instance/rolling-update.properties` `X`
   als älteste verträgliche Version nennt — die Version, von der das Release ein Rolling Update
   zusagt;
3. sonst `main` 24 Stunden vor der Basis (Merge-Base mit `origin/main`: für einen PR das `main`, von
   dem er abzweigt, auf `main` der eigene Stand), frühestens der Merge von #2964, der erste Stand mit
   Migrationsmodus und Compose-Variante.

Solange kein Release ein Versionsfenster nennt, prüft die Suite also den Sprung über einen Tag
Entwicklung auf `main`; ab dem ersten Release mit Rolling Update den zugesagten Sprung.

```bash
# Docker Compose (aus e2e/): baut die Images der aktuellen und der Vorgängerversion selbst, wenn
# E2E_MULTI_BACKEND_IMAGE, E2E_MULTI_FRONTEND_IMAGE, E2E_MULTI_PREVIOUS_BACKEND_IMAGE und
# E2E_MULTI_PREVIOUS_FRONTEND_IMAGE fehlen; E2E_MULTI_PHASES=fresh oder rolling wählt eine Phase.
# Eigenes Compose-Projekt opaa-multi, Ports 13002 (Frontend), 18183/18184 (Instanzen), 18026
# (mailpit), 18090 (ai-stub), Subnetz 172.26.0.0/16; nach dem Lauf wieder abgebaut
pnpm install
pnpm run test:multi-instance

# kind (aus dem Wurzelverzeichnis): erwartet die vier Images im Cluster und e2e/node_modules
#   kind load docker-image opaa-ci/backend:ci opaa-ci/frontend:ci \
#     opaa-ci/backend:previous opaa-ci/frontend:previous
# Namensräume opaa-multi und multi-test werden vor und nach dem Lauf gelöscht
deploy/helm/ci/multi-instance-test.sh
```

In CI läuft `.github/workflows/multi-instance.yml` je Plattform als eigener Job: nächtlich, auf
Zuruf und an einem PR über das Label `multi-instance-suite`; kein Required Check. Der Speicherbedarf
liegt je Plattform bei etwa 4–5 GB.

## Image-Rauchtest

```bash
# Image-Rauchtest (aus dem Repo-Wurzelverzeichnis): beide Images für die Architektur des
# Docker-Hosts bauen und gehärtet starten: zuerst das Backend-Image mit OPAA_MODE=migrate und
# danach mit OPAA_MODE=await-migration (beide müssen mit Exit-Code 0 enden), dann das Backend ohne
# Migration gegen pgvector bis Readiness UP samt Healthcheck des Images, Frontend davor bis
# /api/health; in CI als Job image-smoke-arm64 (ci.yml) auf einem arm64-Runner
docker build -t opaa-smoke/backend -f backend/Dockerfile .
docker build -t opaa-smoke/frontend -f frontend/Dockerfile .
.github/scripts/image_smoke_test.sh opaa-smoke/backend opaa-smoke/frontend
# Härtung des Frontend-nginx ohne Backend: ein Echo statt des Backends belegt, dass vom Client
# gesetzte Forwarded/X-Forwarded-Host/-Port/-Prefix nicht ankommen; dazu Permissions-Policy auf
# jeder Antwort, die Startprüfung von OPAA_CSP_CONNECT_SRC_EXTRA und mit OPAA_BACKEND_RESOLVER
# die Auflösung zur Laufzeit: zwei Container hinter einem Namen bekommen beide Anfragen, ein
# entfernter fällt ohne Neustart heraus. Stoppt einer unter parallelen Anfragen, beantwortet der
# andere jede binnen weniger Sekunden, ohne 5xx; langsame und gestreamte Antworten sowie Uploads
# bis zu den Grenzen kommen unverändert an. In CI im selben Job
.github/scripts/frontend_hardening_test.sh opaa-smoke/frontend
```
