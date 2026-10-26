# Build und CI

Nachschlagewerk zu Befehlen außerhalb des täglichen Builds. Die Grundbefehle (Build, Test, Formatierung,
Start von Backend und Frontend) und die Pre-Push-Checkliste stehen in [AGENTS.md](../../AGENTS.md#build--test).

## Frontend mit Mocks

```bash
# aus frontend/
VITE_ENABLE_MOCKS=true pnpm run dev     # Dev-Server mit MSW-Mocks
# Im dev-Auth-Modus laufen Anfragen als "dev-admin"; auf einen regulären Nutzer
# wechseln mit http://localhost:5173/?devUser=dev-user
```

`pnpm run build:prototype` baut dasselbe Frontend mit Mocks als statischen, klickbaren Prototyp
ohne Service Worker und ohne Server (Hash-Routing, relative Pfade), siehe
[Klickbarer Prototyp](../../e2e/ui-lab/README.md#klickbarer-prototyp).

**Mock-Szenarien.** `?mockScenario=default|small|scale|empty|slow|failed|partial` an einer
beliebigen URL wählt den Fixture-Satz, z. B. viele Einträge mit langen Namen (`scale`), leere
(`empty`), langsame (3 s) oder fehlschlagende Antworten; die Wahl bleibt für den Tab bestehen,
`?mockScenario=default` setzt zurück. Bisher folgen die Wissensbibliotheken samt Konnektoren und
die Gruppen dem Szenario. Tabelle und Erweiterung um weitere Bereiche:
[e2e/ui-lab/README.md](../../e2e/ui-lab/README.md#mock-szenarien).

**UI-Lab.** Werkzeuge für Screenshots, Kennzahlen, Videos und Variantenvergleiche gegen dieses
Frontend liegen unter [`e2e/ui-lab/`](../../e2e/ui-lab/README.md); den Ablauf für die
Neugestaltung eines Bereichs beschreibt der Skill `.claude/skills/ui-lab/SKILL.md`, den
Prüfmaßstab die [UI-Prüfliste](../design/ui-pruefliste.md).

## Integrationstest-Suiten

Nicht Teil von `./gradlew build` und `./gradlew test`. Welche Teststufe ein Konnektor bekommt und
wann er eine eigene Suite braucht, regelt [ADR-0057](../decisions/0057-teststufen-fuer-konnektoren.md).

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
OPAA_SP_IT_ENV_FILE=… ./gradlew sharePointIntegrationTest
                                  # SharePoint-Konnektor gegen einen echten Microsoft-365-Mandanten
                                  # mit Sites.Selected, nur lesend; läuft nur lokal beim Maintainer,
                                  # Zugangsdatei außerhalb des Repos, kein CI-Job und kein Secret.
                                  # Einrichtung: README.md im Testpaket indexing.source.sharepoint
                                  # (ADR-0047)
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
deploy/helm/ci/restore-test.sh          # Wiederherstellungsprobe (docs/handbuch/betrieb/sicherung-und-wiederherstellung.md,
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
pnpm run test:local-auth                # lokale Anmeldung im Modus oidc, installiert aus e2e/installation/
pnpm run test:installation              # Einrichtungs-Assistent gegen einen Stack im Installationsmodus
```

Die Stacks im Modus `oidc` (local-auth, Demo-Smoke, Zwei-Instanzen, ohne Internet) installieren sich
mit den Antwortdateien unter `e2e/installation/` (ADR-0060); `test:installation` startet ohne
Antwortdatei mit gesetztem Token. Beide Ziele laufen in `.github/workflows/local-auth-e2e.yml`,
an einem PR über das Label `local-auth-suite`. Das Token des Ziels `installation`
(`e2e/installation.env`) und die Passwörter unter `e2e/installation/` sind öffentliche Testwerte; sie
stehen auch in den Playwright-Traces und Berichten, die die Workflows als Artefakt hochladen, und
gehören in keine andere Installation.

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
   dem er abzweigt, auf `main` der eigene Stand), frühestens der Merge von #3206, der erste Stand,
   der das Embedding-Modell in der Datenbank hält. Eine ältere Installation übernimmt ihr
   Embedding-Modell nicht ([ADR-0060](../decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md),
   Entscheidung 10; eine Datenbank eines solchen Zwischenstands von `main`, etwa eines Demo-Stacks,
   startet deshalb einmal mit einem Stand ab dem Merge von #3206 oder wird neu aufgesetzt), ebenso
   wenig eine mit Liquibase migrierte
   ([ADR-0056](../decisions/0056-lizenz-der-migrationsbibliothek.md), Entscheidung 2). Ab dieser
   Untergrenze installiert auch die Vorgängerversion aus der Antwortdatei.

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

## Betrieb ohne Internet

Das Ziel `offline` (`e2e/scripts/offline.mjs`, Szenario unter `e2e/offline/`) belegt, dass die
Installation ohne Internetzugang arbeitet. Der Stack aus `docker-compose.yml` läuft mit
`e2e/docker-compose.offline.yml` in einem Compose-Netz mit `internal: true`: ohne Gateway, jede
Verbindung an eine Adresse außerhalb des Subnetzes scheitert sofort.

- **Vorab bereitgestellt:** OPAA-Images werden gebaut (oder mit `E2E_SKIP_BUILD=true` übernommen),
  Drittimages gezogen; danach startet der Stack mit `--pull never`. Das Modell ist `ai-stub` im
  selben Netz.
- **Ablauf:** Anmeldung des lokalen Notanker-Kontos, Bibliothek über den Assistenten, Upload,
  Zuordnung der Bibliothek zum Standard-Space über die API, Frage ohne Referenz, Fundstelle im
  Belegfenster; danach eine zweite Bibliothek ohne Space mit `formatdokument.xlsx`, `.html` und
  `.eml`, damit die Parser dieser Formate im abgeschotteten Netz laufen. Der Lauf ist der einzige
  Nachweis für Abrufe eingebundener Bibliotheken; der Strukturtest des Backends sieht nur die
  Clients im Code von OPAA (ADR-0052, Entscheidung 8).
- **Erkennung:** Jeder Container der Installation löst Namen über `egress-dns` auf
  (`e2e/offline/egress-dns.mjs`). Dockers eingebautes DNS beantwortet die Dienstnamen selbst und
  reicht nur fremde Namen weiter; der Wächter zeichnet sie auf und antwortet mit NXDOMAIN. Der
  Browser-Kontext bricht jeden Abruf einer fremden Origin ab und zeichnet ihn auf. Nach dem Lauf
  durchsucht `offline.mjs` die Container-Logs nach Verbindungsversuchen ohne Route
  (`Network is unreachable`, `No route to host`) — der Weg einer Verbindung an eine IP-Adresse ohne
  Namensauflösung, den der Wächter nicht sieht. Ein solcher Versuch fällt nur auf, wenn sein Fehler
  im Log steht; eine Positivprobe für diesen Weg gibt es nicht.
- **Positivprobe:** Der Verbindungstest des Assistenten gegen `egress-probe.offline-check.example`
  lässt das Backend einen fremden Namen auflösen; der Lauf scheitert, wenn der Wächter ihn nicht
  aufzeichnet. Alle anderen Aufzeichnungen müssen leer bleiben.
- **Zugang von außen:** nur über `relay` (`e2e/offline/relay.mjs`), das als Testgerüst in beiden
  Netzen steht und Port 13003 an das Frontend und 18053 an das Protokoll des Wächters weiterreicht.
- **Ergebnis:** `e2e/test-results/offline-result.json` mit Version, Datum, den Aufzeichnungen und
  den Funden der Logsuche; die CI schreibt es in die Zusammenfassung des Laufs.

```bash
# aus e2e/: eigenes Compose-Projekt opaa-offline, Subnetz 172.24.0.0/16, nach dem Lauf abgebaut
pnpm install
pnpm exec playwright install chromium   # einmalig
pnpm run test:offline
```

**Nicht abgedeckt:** ein echter Modellserver (ob er ohne Internet startet, ist dessen Sache),
weitere Formate (PDF, Word- und Präsentationsdateien, Bilder), Reranking, Transkription,
zeitgesteuerte Aufgaben, Konnektoren zu Quellen, Identitätsanbieter, Mailversand und Kubernetes.

`.github/workflows/offline.yml` fährt das Ziel nächtlich, auf Zuruf und an einem PR über das Label
`offline-suite`; kein Required Check.

## Image-Rauchtest

```bash
# Image-Rauchtest (aus dem Repo-Wurzelverzeichnis): beide Images für die Architektur des
# Docker-Hosts bauen und gehärtet starten: zuerst das Backend-Image mit OPAA_MODE=migrate und
# danach mit OPAA_MODE=await-migration (beide müssen mit Exit-Code 0 enden), dann das Backend ohne
# Migration gegen pgvector bis Readiness UP samt Healthcheck des Images, Frontend davor bis
# /api/health und der gemeldeten offenen Installation (ohne Antwortdatei); in CI als Job
# image-smoke-arm64 (ci.yml) auf einem arm64-Runner
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
