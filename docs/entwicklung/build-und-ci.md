# Build und CI

Nachschlagewerk zu Befehlen außerhalb des täglichen Builds. Die Grundbefehle (Build, Test, Formatierung,
Start von Backend und Frontend) und die Pre-Push-Checkliste stehen in [AGENTS.md](../../AGENTS.md#build--test).

## Aufbau der CI und Shards

`ci.yml` startet zuerst den Job `paths`, der nur den Pfadfilter enthält; alle Test- und Build-Jobs hängen
an ihm und starten ohne Warten auf die Repo-Prüfungen. Die Pflicht-Checks sind Aggregatoren:

- `changes` führt die Repo-Prüfungen aus und scheitert auch, wenn `paths` scheitert.
- `backend` sammelt `backend-build`, `openapi-bundle-parity` und die Testshards `backend-test (api | indexing |
  connection | migration | core)`. Die Paketlisten stehen in `testShards` in `backend/build.gradle.kts`, `core`
  ist das Komplement. Jeder Shard schreibt seine langsamsten Testklassen ins Job-Summary
  (`.github/scripts/slowest_test_classes.py`); danach die Listen ausbalancieren.
- `frontend` sammelt `frontend-check` (Format, Lint, Lizenzen, Themengrenzen, Build) und drei
  vitest-Shards `frontend-test (1…3)`. Einen Shard lokal nachstellen: `pnpm run test --shard=1/3`.

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

**Mock-Profile.** `?mockProfile=admin|user|auditor|signed-out` wählt, wer im Mock angemeldet ist;
die Wahl bleibt wie das Szenario für den Tab bestehen, `?mockProfile=admin` setzt zurück. `user` ist
ein Konto ohne Verwaltung, `auditor` die Rolle Revision mit ihren Seiten unter `/revision/…`, und
`signed-out` meldet niemanden an und bietet zwei Identitätsanbieter sowie lokale Konten mit
Selbstregistrierung und Passwort-Rücksetzung an, sodass Anmelde-, Registrier- und Passwortseiten
erreichbar sind. Den Einrichtungs-Assistenten öffnet `?mockInstallation=pending`, mit allen
optionalen Schritten `?mockInstallation=embedding`; `?mockInstallation=done` kehrt zurück. Im
Szenario `slow` läuft die Chat-Antwort nach zwei Sekunden Wort für Wort ein.

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
./gradlew doclingIntegrationTest # Texterkennungs-Client gegen ein echtes docling-serve im
                                  # Container (io.opaa.integration.docling.*): liest die
                                  # mitgelieferte Testseite mit den Optionen des Betriebs, trennt
                                  # falschen Schlüssel und nicht erreichbaren Dienst; braucht nur
                                  # Docker, OPAA_OCR_IT_IMAGE ersetzt das Image (etwa durch das
                                  # gelieferte opaa-ocr). Nicht Teil von build/test: CPU-Image
                                  # mehrere GB. In CI nightly und per Label "docling-suite"
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

## Regressionssuite für feindliche Eingaben

Präparierte Dateien, SSRF-Ziele und Log-Hygiene: Aufbau, Grenzen und was ein neuer Parser oder
Konnektor ergänzt, stehen in [regressionssuite-eingaben.md](regressionssuite-eingaben.md). Teil von
`./gradlew check`, nicht von `./gradlew test`:

```bash
# aus backend/
./gradlew hostileInputTest   # präparierte Dateien gegen die Formate, eigene JVM mit 512 MB Heap
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
helm template opaa . -f ci/ocr-values.yaml      # Texterkennungsdienst opaa-ocr mit Schlüssel und
                                                # Ausgangs-Policies (ocr.service)

# Chart-CI (aus dem Repository-Wurzelverzeichnis; Workflow .github/workflows/helm-chart.yml)
deploy/helm/ci/static-check.sh          # helm lint + template mit jedem Wertesatz unter
                                        # deploy/helm/opaa/ci/*-values.yaml, kubeconform gegen die
                                        # drei unterstützten Kubernetes-Versionen, Prüfung der
                                        # Abbruchregeln und Trockenlauf der Release-Skripte;
                                        # braucht helm, kubeconform, openssl, curl und jq. Neue
                                        # Wertesätze dort ablegen, sie werden automatisch geprüft
deploy/helm/ci/package-chart.sh v1.2.3 <ziel>
                                        # Chart packen wie der Release-Lauf (publish-images.yml):
                                        # Version und appVersion aus dem Tag, prüft die Image-Tags
                                        # der Vorgabewerte, auch des Texterkennungsdienstes;
                                        # veröffentlicht nichts. Der Release-Lauf übergibt die
                                        # Digests von Backend, Frontend und opaa-ocr. Das Tag-Format
                                        # steht allein in release-version.sh, die Existenzprüfung
                                        # von Images und Chart in GHCR in oci-published.sh (nur
                                        # HTTP 404 gilt als frei), der Inhaltsvergleich des Charts
                                        # vor der Attestierung in chart-matches.sh
deploy/helm/ci/install-test.sh          # Installationstest: ct install mit der Erprobungsdatenbank
                                        # des Charts und eingeschalteten NetworkPolicies in einen
                                        # Namespace mit Pod Security Standard restricted; wartet auf
                                        # Datenbank, Backend und Frontend und führt helm test aus,
                                        # ohne LLM. Signaturgeheimnis und Schlüssel erzeugt es je
                                        # Lauf. Braucht kubectl, helm, ct, openssl und
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
TRIAL_OCR=true examples/kubernetes-trial/trial.sh up
                                        # zusätzlich der Texterkennungsdienst opaa-ocr des Charts
                                        # (ocr.service) mit erzeugtem Schlüssel, Texterkennung
                                        # freigegeben; rund 4 GiB Arbeitsspeicher mehr
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
pnpm run test:ocr                       # Texterkennung mit echtem opaa-ocr, als Video (siehe unten)
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
| `07-wechsel-des-embedding-modells` | Wechsel des Embedding-Modells auf A bestätigt gilt sofort auf B, eine zweite Bestätigung auf B scheitert mit `409`, Verwerfen auf B gilt auf A; ein Wechsel auf B schaltet um, und beide führen das neue Modell |

Zwei Phasen, je mit leerer Datenbank: `fresh` startet die aktuelle Version (01–04, 06, 07), `rolling`
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
   Untergrenze installiert auch die Vorgängerversion aus der Antwortdatei. Liegt der letzte Stand,
   der eine Baseline umschreibt ([Baselines](../../backend/docs/migrationen.md#baselines)), später,
   ist er die Untergrenze: Keine ältere Historie passt zu den neuen Prüfsummen. Auf einem solchen
   Stand und in den 24 Stunden danach prüft Szenario 05 also das Update der Version auf sich selbst.

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

# kind (aus dem Wurzelverzeichnis): erwartet die vier Images im Cluster und e2e/node_modules,
# braucht kubectl, helm, node, pnpm und openssl (Signaturgeheimnis und Schlüssel je Lauf)
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

## Texterkennung gescannter Dokumente

Die Texterkennung (ADR-0069) ist in der E2E-Suite zweimal geprüft, beide Male mit demselben
Testdokument: der verscannten Baugenehmigungsgebührensatzung aus `eval/corpus/verwaltung`
(synthetisch, CC0) mit Gebührentabelle, §-Gliederung und einer Seite mit `/Rotate 90`. Sie steht
unter `demo/seed/e2e-data/text-recognition/`. Den Rahmen jeder Fundstelle vergleichen beide mit dem
Ort, an dem Stelle und Abschnitt gesetzt wurden (`satzung-scan.layout.json`), nicht mit den Boxen
der Erkennung. Gerahmt wird der ganze Abschnitt, aus dem die Fundstelle stammt: der Tabellenkörper
ohne Kopfzeile, § 13 von der Überschrift bis Absatz 3, der Textblock der gedrehten Seite.

- **Suite:** `e2e/tests/text-recognition.spec.ts` läuft mit `ocr-stub` als docling-serve. Der
  Ersatzdienst spielt das DoclingDocument ab, das ein echter `opaa-ocr`-Lauf aus dem Testdokument
  gemacht hat (`satzung-scan.docling.json`). Dabei prüft er Schlüssel und Optionen. Weil die
  Antwort feststeht, liegt jede Kante des Rahmens höchstens 0,02 Seite neben der des Abschnitts. Geprüft
  werden die Szenarien Ausgeschaltet, Einrichten, Hochladen, Fragen und Fundstelle an drei Stellen
  sowie Nachholen. Für das Nachholen wartet der letzte Schritt auf den Hintergrunddurchgang, der
  im E2E-Stack alle 20 Sekunden läuft (`OPAA_OCR_CATCH_UP_INTERVAL`, Vorgabe fünf Minuten).
- **Abnahme:** Das Ziel `ocr` startet den Stack der Suite mit `e2e/docker-compose.ocr.yml`, also
  mit dem echten `opaa-ocr` in einem internen Netz, Schlüssel je Lauf. Es führt
  `e2e/ocr/tests/abnahme.spec.ts` aus: Einrichten, Hochladen, Fragen und Fundstellen. Das Video wird
  in ruhigem Tempo und mit sichtbarem Zeiger aufgezeichnet. Weil eine echte Erkennung schwankt,
  muss der Rahmen hier nur die Stelle enthalten und innerhalb des Abschnitts liegen, beides auf
  0,02 Seite genau.

```bash
# aus e2e/: Compose-Projekt opaa-ocr-e2e, Frontend auf 13008, nach dem Lauf abgebaut;
# OPAA_OCR_IMAGE wählt das Image (Vorgabe ghcr.io/criew/opaa-ocr:main), Video unter test-results-ocr/
pnpm run test:ocr

# Antwort des echten opaa-ocr für den Ersatzdienst neu aufzeichnen (aus dem Wurzelverzeichnis),
# nötig nach einer Änderung am Testdokument oder an den Optionen
demo/seed/e2e-data/text-recognition/capture_docling.sh ghcr.io/criew/opaa-ocr:main
```

`.github/workflows/ocr-acceptance.yml` („OCR-Abnahme“) läuft auf Zuruf und an einem PR über das Label
`ocr-abnahme`. Der Workflow baut `opaa-ocr` aus `ocr/Dockerfile`, zeichnet die Antwort zum
Testdokument als Artefakt `ocr-docling-json` auf und lädt Video, Screenshots und Bericht als
`ocr-abnahme` hoch. Er ist kein Required Check.

## ZAP-Baseline-Scan

```bash
# Passiver ZAP-Scan eines anonymen Aufrufers gegen einen Stack im Betriebsmodus oidc (aus dem
# Repo-Wurzelverzeichnis; Compose-Projekt opaa-zap, Frontend auf 13010, nach dem Lauf abgebaut);
# ZAP_SKIP_BUILD=true startet vorhandene Images opaa-zap-backend:latest und opaa-zap-frontend:latest
.github/scripts/zap_baseline.sh ghcr.io/zaproxy/zaproxy:2.17.0 zap-out
python3 .github/scripts/zap_rules.py summary --rules .github/zap/rules.yaml \
  --report zap-out/zap-report.json --zap-exit $?
```

Workflow `.github/workflows/zap-baseline.yml` (nächtlich, manuell und in PRs, die den Scan
ändern), Unterdrückungen in `.github/zap/rules.yaml`, Verfahren in
[zap-baseline.md](zap-baseline.md).

## Image-Rauchtest

```bash
# Image-Rauchtest (aus dem Repo-Wurzelverzeichnis): beide Images für die Architektur des
# Docker-Hosts bauen und gehärtet starten: zuerst das Backend-Image mit OPAA_MODE=migrate und
# danach mit OPAA_MODE=await-migration (beide müssen mit Exit-Code 0 enden), dann das Backend ohne
# Migration gegen pgvector bis Readiness UP samt Healthcheck des Images, Frontend davor bis
# /api/health und der gemeldeten offenen Installation (ohne Antwortdatei); danach tragen eigene
# Dateien des Frontends und durchgereichte Backend-Antworten (auch eine 401) je genau einmal
# X-Content-Type-Options: nosniff, X-Frame-Options: DENY, Cross-Origin-Opener-Policy und
# Cross-Origin-Resource-Policy (same-origin) und die CSP mit script-src 'self',
# object-src 'none' und frame-ancestors 'none'; in CI als Job image-smoke-arm64 (ci.yml) auf einem
# arm64-Runner
docker build -t opaa-smoke/backend -f backend/Dockerfile .
docker build -t opaa-smoke/frontend -f frontend/Dockerfile .
.github/scripts/image_smoke_test.sh opaa-smoke/backend opaa-smoke/frontend
# Härtung des Frontend-nginx ohne Backend: ein Echo statt des Backends belegt, dass vom Client
# gesetzte Forwarded/X-Forwarded-Host/-Port/-Prefix nicht ankommen; dazu Permissions-Policy,
# Cross-Origin-Opener-Policy und Cross-Origin-Resource-Policy auf jeder Antwort, die
# Startprüfung von OPAA_CSP_CONNECT_SRC_EXTRA und mit OPAA_BACKEND_RESOLVER die Auflösung zur
# Laufzeit: zwei Container hinter einem Namen bekommen beide Anfragen, ein
# entfernter fällt ohne Neustart heraus. Stoppt einer unter parallelen Anfragen, beantwortet der
# andere jede binnen weniger Sekunden, ohne 5xx; langsame und gestreamte Antworten sowie Uploads
# bis zu den Grenzen kommen unverändert an. In CI im selben Job
.github/scripts/frontend_hardening_test.sh opaa-smoke/frontend
```

## Parser-Fuzzing

Eine zeitbegrenzte Kampagne schickt erzeugte und verfälschte Dateien aller zugelassenen Formate
über den Produktionsweg: Routing der `DocumentFormatRegistry`, `DocumentFormatRunner` mit Anhängen
in den Grenzen von `opaa.indexing.attachments.*`, danach `readProperties` wie beim erneuten Auslesen
der Metadaten. Die Konfiguration kommt aus `application.yml`, ohne Datenbank und ohne Modell.

- **Befund** ist ein `OutOfMemoryError`, ein Lauf über die Eingabegrenze (Vorgabe eine Minute),
  ein Throwable, das den Weg verlässt, statt als `PARSE_FAILED` gebucht zu werden, oder eine
  zurückgelassene Temp-Datei eines Anhangs. Ein gebuchter Parserfehler ist kein Befund.
- **Korpus:** je zugelassenem Format mindestens eine Probe. Eingecheckt unter
  `backend/src/test/resources/fuzz/corpus/` (höchstens 2 MiB, `-text` in `.gitattributes`) liegen
  die Proben von `FuzzCorpusGenerator`; der Generator schreibt ZIP-Einträge mit fester Zeit und
  setzt die Paketdaten von Office-Dateien fest. Gleiche Quelle ergibt gleichen Inhalt, gleiche
  Bytes aber nur mit derselben zlib: ein anderes JDK kann denselben Inhalt anders komprimieren.
  `FuzzCorpusTest` vergleicht deshalb über `SeedComparison`: ZIP-Container Eintrag für Eintrag
  (Name, Reihenfolge, Methode, Zeit, entpackter Inhalt; Zentralverzeichnis, Zusatzfelder und
  Kommentare nicht), eine PDF mit Flate-Stream mit entpackten Streams samt Bytes hinter dem
  Deflate-Ende; maskiert werden dort nur die Länge eines Flate-Streams sowie xref-Offsets und
  `startxref`, die in ihrer eigenen Datei auf ihr Objekt oder ihre xref zeigen. Alles andere,
  auch eine PDF ohne Flate-Stream, vergleicht er Byte für Byte; die Meldung nennt Datei und ersten
  abweichenden Eintrag. Nach einer
  Änderung am Generator `./gradlew writeParserFuzzCorpus` ausführen und die Dateien einchecken.
  Dazu kommen beim Laden die präparierten Dateien der Regressionssuite (`HostileFiles`) in kleiner
  Ausprägung als `hostile-*`-Proben (`HostileSeeds`), ohne eingecheckt zu werden. Auch sie sind
  mit demselben JDK bei jedem Laden dieselben Bytes: die Trailer-ID einer PDF wird in gleicher Länge genullt, und
  eingebettete Pakete (auch in OLE2-Objekten einer Präsentation) bekommen feste Zeiten und Daten;
  `HostileSeedsTest` prüft beides.
- **Mutationen:** `Mutators` kippt Bits, setzt Grenzwerte, löscht, verdoppelt und spleißt
  Bereiche, wiederholt Zeilen, verschachtelt Auszeichnung, verändert einzelne ZIP-Einträge und
  verpackt eine Datei als Anhang einer Mail; jede sechzehnte Eingabe bekommt eine fremde Endung.
  Seed der Kampagne und Nummer der Eingabe bestimmen eine Eingabe vollständig.
- **In jedem PR** spielt `FuzzCorpusTest` (Shard `core`) jede Probe einmal unverändert über den
  Produktionsweg ab, rund zehn Sekunden, alle Tests in einem gemeinsamen Produktionsweg; eine
  aufgenommene Befund-Datei wird so zum Regressionstest. Die Kampagne selbst läuft nie im PR.
- **Abbruch der JVM:** vor jeder Eingabe schreibt die Kampagne sie als `current-input` samt
  `current-input.finding` (Seed, Iteration, Mutationen) neben die Befunde und löscht beides
  danach. Bleibt `current-input` liegen, hat diese Eingabe die JVM beendet (Speicher, Kill).
- **Nächtlich** um 1:35 UTC und auf Zuruf (`workflow_dispatch` mit Dauer und Seed) startet
  `.github/workflows/parser-fuzzing.yml` zehn Minuten Kampagne, Seed ist die Run-ID. Befunde und
  `summary.txt` (Läufe je Format, Ergebnisse, langsamste Eingaben) liegen im Artefakt
  `parser-fuzzing-findings`; je Befund die Eingabe und eine `.finding`-Datei mit Seed, Mutationen
  und Stacktrace. Kein Required Check. Das Log zeigt nur die Zeilen mit `[parser-fuzz]`: Seed und
  Zusammenfassung.
- **Alarm:** Scheitert ein nächtlicher Lauf oder wird er abgebrochen, öffnet der Workflow ein Issue
  (Labels `ci`, `bug`, `security`, Marker `<!-- parser-fuzzing-alert -->`) mit Seed, Lauf-Link,
  `summary.txt` und Artefaktname, oder kommentiert das offene. Der nächste nächtliche Lauf ohne
  Befund kommentiert und schließt es. Ein Lauf über `workflow_dispatch` berührt den Alarm nie.

```bash
# aus backend/: Kampagne, Befunde unter build/fuzz-findings/
./gradlew parserFuzzTest -PfuzzDuration=PT2M -PfuzzSeed=3379
# eine Befund-Datei oder ein ganzes Verzeichnis erneut abspielen
./gradlew parserFuzzTest -PfuzzReplay=/pfad/zu/001-escaped_throwable-90.txt
# Grenze je Eingabe ändern (Vorgabe PT1M)
./gradlew parserFuzzTest -PfuzzInputTimeout=PT30S
```

Ein Befund wird wie jeder Fehler behoben: Test, der ihn reproduziert, und die kleinste Eingabe,
die ihn auslöst, als Probe in den Generator. Jazzer ist bewusst nicht eingebunden: der Fuzzer
bringt einen nativen Agenten und eine eigene JUnit-Anbindung mit, und die bekannten
Angriffsmuster der Formate erreicht ein Mutationsfuzzer über die Proben ebenso.

## Löschvollständigkeit (Sentinel-Tests)

`DocumentDeletionSentinelIntegrationTest` und `ChatDeletionSentinelIntegrationTest` laufen mit den
Integrationstests (Docker). Sie schreiben einen eindeutigen Markertext überall hin, wo ein Dokument
bzw. ein Chat abgeleitete Daten hinterlässt (Dateiname, Inhalt, Vektor- und Volltext-Chunks,
Metadatenwert, Schlagwort und verworfener Modellvorschlag; Frage, Antwort, erzeugter Titel,
Notiz und gelesener Anhang mit seinem Text), prüfen vorab, dass der Marker in diesen Spalten
angekommen ist, löschen das Objekt über den Dienst der Anwendung und durchsuchen danach mit
`io.opaa.test.SentinelScan` jede Text-, JSON-, Array-, `tsvector`- und `bytea`-Spalte jeder
Tabelle des Schemas, Partitionen über ihre Elterntabelle. Die Spalten kommen aus
`information_schema`; eine neue Tabelle oder Spalte ist ohne Pflege erfasst. Was den Marker nach
dem Löschen behalten darf, steht mit Begründung in der Liste `RETAINED` des Tests (heute nur das
Revisionsprotokoll); ein neuer Treffer ist eine Löschlücke und wird behoben, nicht in die Liste
eingetragen. Geprüft wird nur die Datenbank: Die Ablage der Originale (Dateisystem oder S3) und
der Gesprächsspeicher im Prozess liegen außerhalb des Scans; das Löschen des Originals sichern
die Tests von `LibraryDocumentService` ab.

## Sicherheitsinvarianten als Architekturtests

`io.opaa.architecture.SecurityInvariantsTest` läuft in jedem Build und prüft mit ArchUnit:

- Verbindungen nach außen nur über `RedirectFollowingFetcher` und `BoundedDownloader`, die das Ziel
  prüfen. Als Verbindung zählen `HttpClient`, `RestClient`, `RestTemplate`, `WebClient`,
  `URL.openConnection`, Jsoup, SMB, Sockets (auch `new Socket(host, port)` und `SSLSocket`, das
  beim Erzeugen verbindet) und SMTP (`MailSender#send`, `JavaMailSenderImpl#testConnection`, `jakarta.mail` `Service#connect`
  und `Transport#send`). Der SMTP-Server ist ein gespeichertes Ziel, das `MailService` und
  `MailConnectionTester` nach ADR-0063 prüfen; sie stehen begründet in der Ausnahmeliste;
- gespeicherte Geheimnisse werden nur in `ModelEndpointAccess` und `ConnectionSecrets`
  entschlüsselt, die das Geheimnis an sein Ziel binden. Ausgenommen ist nur der Mechanismus selbst,
  eine ausdrückliche Klassenliste (`SettingsEncryptor`, `CredentialsEncryptor`,
  `EncryptedCredentials`, `EncryptionKeyring`), nicht das ganze Paket `io.opaa.security`;
- jeder Controller-Handler hat `@PreAuthorize` (oder `@PostAuthorize`, `@Secured`,
  `@RolesAllowed`) oder einen Parameter für den Aufrufer (`@Caller`, `CurrentUser`, …). Eine
  Annotation am Handler geht der an der Klasse vor; `permitAll()`, `isAnonymous()` und `ANONYMOUS`
  zählen nicht als Prüfung;
- `io.opaa.format` kompiliert kein Muster je Aufruf oder je Objekt (`String.matches`, `replaceAll`,
  `replaceFirst`, `split`, `Pattern.matches`, `Pattern.compile` außerhalb des statischen
  Initialisierers, auch in Konstruktoren); ein `split` an einem einzelnen Zeichen und der
  Konstruktor eines Enums stehen mit Begründung in der Ausnahmeliste;
- Text, den eine Person, ein Dokument oder eine frühere Antwort bestimmt, kommt nur über
  `PromptText` (unsichtbare Zeichen und Marker bis zum Fixpunkt entfernen, zwischen Marker setzen)
  oder `ContentFence` (Zaun mit Kennung je Aufruf) in einen Prompt. Als Prompt-Bau zählen
  `UserMessage`, `SystemMessage` und `Prompt` (Konstruktor und Builder) sowie `system` und `user`
  einer `ChatClient`-Anfrage; gemeldet wird eine Methode (samt ihrer Lambdas), die selbst keinen
  der beiden Bausteine aufruft. Woher ein Text stammt, sieht der Bytecode nicht: Die Ausnahmeliste
  (`PROMPTS_PAST_THE_BLOCKS`) nennt je Methode, wo ihr Text bereinigt wurde, eine neue Stelle muss
  diese Entscheidung ausdrücklich treffen. Grenze: Ruft eine Methode einen Baustein auf, prüft die
  Regel nicht, ob gerade der bereinigte Text in der Nachricht landet;
- Sicherheitszustand wird nicht als veraltete ganze Zeile zurückgeschrieben (siehe unten).

Gezählt werden Aufrufe und Methodenreferenzen (`client::send`, `encryptor::decrypt`), gemeldet
wird die Methode, in der sie stehen; ein Lambda zählt zu seiner umgebenden Methode. Jede Regel hat
eine Ausnahmeliste mit einer Begründung je Methode (`Klasse#methode`); ein Eintrag, dessen Methode
die Regel nicht mehr bricht, lässt den Test ebenfalls scheitern. Die Handler ohne eigene Prüfung
stehen unter `withoutHandlerCheck` in `backend/src/test/resources/security/anonymous-allowlist.yaml`,
neben den anonym erreichbaren Pfaden. Jede Regel hat in `SecurityInvariantsFixtureTest` eine
Attrappe, die sie bricht, als Nachweis, dass sie anschlägt.

Nicht erfasst sind Verbindungen, die eine Bibliothek selbst aufbaut (Spring AI, AWS SDK, JDBC, das
Laden des JWK-Sets durch Nimbus), und Einstiege ohne Controller-Handler: `RouterFunction`, der
MCP-Endpunkt, Actuator und Pfade, die ein Filter beantwortet; ob sie ohne Anmeldung erreichbar sind,
prüft die Angriffsfläche ohne Anmeldung.

`FormatPatternBacktrackingTest` jagt jedes Muster in `io.opaa.format`, das in einem statischen
Feld oder einem Feld einer Enum-Konstante steht, mit faulem, verschachteltem oder mehr als einem
unbeschränkten Quantor mit gepumpten Eingaben gegen eine Zeitgrenze; jedes solche Muster nennt dort
seine Angriffseingaben.

### Kein Ganzzeilen-Schreiben von Sicherheitszustand

Hibernate schreibt eine geänderte Entity ohne `@DynamicUpdate` mit allen Spalten zurück. Liest ein
Pfad eine Zeile, ändert ein Feld und speichert, überschreibt er alles, was eine andere Transaktion
zwischen Lesen und Schreiben festgeschrieben hat – etwa eine entzogene Rolle, eine Sperre durch den
Verzeichnisabgleich oder einen abgeschalteten Anbieter. `securityStateIsNeverWrittenBackAsAStaleWholeRow`
meldet für jede Entity in `SECURITY_STATE_ENTITIES` ohne `@Version` jeden Aufruf von `save`,
`saveAll`, `saveAndFlush` und `saveAllAndFlush` ihres Repositorys; fehlt auch `@DynamicUpdate`,
zusätzlich jeden Aufruf einer Methode der Entity, die ein eigenes Feld setzt. Der Code der Entity
selbst ist ausgenommen. Jede gemeldete Methode steht in `WHOLE_ROW_WRITERS` mit einem Satz
Begründung: neue Zeile, nur geänderte Spalten in einer Transaktion (`@DynamicUpdate`), Zustand
bewegt sich nur Richtung widerrufen, die Rolle ist der einzige änderbare Zustand, oder eine
Zeilensperre (`FOR UPDATE`). Die Übertragung von Berechtigungen liest die Berechtigung der
Zielgruppe unter Zeilensperre; Änderung und Widerruf einer Dienstkonto-Zustimmung sperren zuerst
die Zeile des Dienstkontos, dann die offene Zustimmung.

`User`, `OidcProvider`, `Group` und `ConnectorTypePolicy` tragen `@Version` und fallen damit aus der
Regel: Ein Schreiben aus einem veralteten Stand, auch das `save` einer abgelösten Instanz, scheitert
mit `OptimisticLockingFailureException`, statt zurückzuschreiben, was es gelesen hat; die
Schreibwege der Verwaltung antworten dann mit `409` und der Bitte, neu zu laden
(`GlobalExceptionHandler`). Die Schreibwege des Systems scheitern daran nicht:

- Die Anmeldung schreibt `lastLoginAt` und die Angaben des Anbieters gezielt
  (`UserRepository#recordLastLogin`, `#recordProviderClaims`) und erhöht die Version dabei nicht;
  eine Anmeldung lässt keine Bearbeitung der Verwaltung scheitern.
- Die Rolle aus dem Token schreibt ein bedingtes `UPDATE` ohne Versionsprüfung
  (`#changeRoleIfStill`), das die Version erhöht; zwei gleichzeitige Anmeldungen derselben Person
  scheitern nicht aneinander, eine Bearbeitung, die die Rolle vorher gelesen hat, dagegen schon.
- Gruppenmitgliedschaften sind eigene Zeilen und erhöhen die Version der Gruppe nicht
  (`@OptimisticLock(excluded = true)`); die Gruppen aus dem Token schreiben nur sie.
- Der Verzeichnisabgleich wendet seinen Stand bis zu dreimal an, wenn seine Transaktion auf eine
  zwischenzeitlich geänderte Gruppe oder ein Konto trifft (`DirectorySyncService#STALE_ROW_ATTEMPTS`),
  und liest dabei jedes Mal neu.

`@DynamicUpdate` bleibt an den vier Entities: Es hält, was ein Schreiber ohne Versionserhöhung
festgeschrieben hat – die gezielten Updates der Anmeldung und die Vorgängerversion während eines
Rolling Updates, die die Spalte nicht kennt.
Wettlauftests mit einer fremden Transaktion zwischen Lesen und Schreiben (`ForeignCommit`) stehen in
`AccountRowWriteIntegrationTest`, `GroupRowWriteIntegrationTest`,
`ConnectorTypePolicyRowWriteIntegrationTest`, `UserServiceStaleRowIntegrationTest`,
`DirectorySyncStaleRowIntegrationTest`, `GroupGrantTransferRowWriteIntegrationTest` und
`ServiceAccountConsentRowWriteIntegrationTest`.

Damit die Liste nicht still veraltet, verlangt `everyPersistentTypeWithASecurityStateFieldIsClassified`
für jede Entity und jeden Repository-Typ mit einem Feld, dessen Name nach Sicherheitszustand klingt
(`role`, `revoked…`, `locked…`, `…LockedAt`, `enabled`, `active`, `status`, `…ExpiresAt`,
`validUntil`, `usableUntil`, `…TokenHash`, `sessionGeneration` u. a., Muster `SECURITY_STATE_FIELD`), einen Eintrag in
`SECURITY_STATE_ENTITIES` oder, begründet, in `NOT_SECURITY_STATE`.

**Grenzen:** Die Regel sieht Aufrufe, keinen Datenfluss; eine Ausnahme gilt für die ganze Methode.
`@DynamicUpdate` schützt nur eine verwaltete Instanz: `save` einer abgelösten Instanz schreibt jede
abweichende Spalte. Ohne `@DynamicUpdate` schreibt auch das Dirty Checking eine geänderte verwaltete
Entity ohne `save` als ganze Zeile; die Regel meldet dann jeden Aufruf einer ändernden Methode.
Vollen Schutz gibt nur `@Version`. Auch dort prüft die Version nur, wer sie mitschreibt: Ein gezieltes
`UPDATE` ohne Versionserhöhung und die Vorgängerversion während eines Rolling Updates lassen ein
späteres Schreiben aus altem Stand durch; dann hält nur `@DynamicUpdate` die übrigen Spalten. Typen,
die nur eingefügt und
gelöscht werden (`GroupMembership`, `CapabilityGrant`), fragt die Regel nicht ab; ein Feld, dessen
Name nicht nach Sicherheitszustand klingt, erfasst die Abdriftprüfung nicht.

## Kein Geheimnis im Log

Wächter-Tests schicken erkennbare Geheimnisse durch echte Anfragen und hängen dabei mit
`io.opaa.test.LogCapture` einen Appender an den Root-Logger auf TRACE; keine Zeile darf einen der
Werte nennen, wörtlich, Base64- oder URL-kodiert, auch nicht in Meldung, Ursache, unterdrückter
Exception oder Stacktrace. Ein Logger mit eigener Stufe (Festlegung in `application.yml`) bleibt
auf dieser Stufe; geprüft wird, was er im Betrieb schreiben würde:

- `SecretLogSentinelIntegrationTest`: Modellschlüssel (eingegeben, mit Steuerzeichen, gespeichert
  und wiederverwendet) beim Verbindungstest gegen einen Server, der mit 401 abweist, gegen einen
  unauflösbaren Host und beim abgewiesenen Anlegen (400); SMTP-Passwort beim Speichern, beim
  fehlschlagenden Test und beim abgewiesenen Speichern; Zugangsdaten einer Quelle beim
  Verbindungstest, auch ein Confluence-Data-Center-Token mit Zeilenumbruch; Code und State einer
  Verbindungszustimmung;
- `McpServerIntegrationTest#noBearerValueReachesALogLine`: Personen-Token, Dienstkonto-Token, ein
  erfundenes Token und ein erfundenes JWT im `Authorization`-Header, erfolgreich und mit 401;
- `LocalAuthLogPrivacyIntegrationTest`: Passwörter (auch falsche), Refresh-Token, lokales
  Zugangs-JWT und die Token der Mail-Links.

Der Logger `org.apache.coyote.http11.Http11InputBuffer` ist in `application.yml` auf INFO
festgelegt: Auf DEBUG schreibt Tomcat jeden Anfragekopf samt `Authorization`. Der HTTP-Client des
JDK zitiert einen ungültigen Header-Wert samt Geheimnis in seiner Exception; deshalb prüft
`io.opaa.common.HeaderValues#requireSafe` den Wert vorher, ohne ihn zu nennen: in
`RedirectFollowingFetcher` für jeden Header (alle Konnektoren), in `ModelEndpointAccess#headerSafe`
für Modellschlüssel, in `KeycloakAdminApi` und `OAuthClient` für Bearer-Token, in
`OpenSandboxClient` für den Schlüssel des Sandbox-Servers. Als zweite
Sicherung ersetzt `ErrorSanitizer` in der WARN-Zeile einer abgewiesenen Anfrage den Wert hinter
`Bearer` und `Basic`, in Anführungszeichen bis zum schließenden Zeichen.

Im Frontend prüft `src/app/secretQueryRoutes.test.ts`, dass jede Seite, die `token`, `code` oder
`state` aus der Query liest (selbst oder über einen exportierten Hook oder eine eingebundene
Komponente), und jede `redirect_uri`
der Anmeldung in den Maskierungs-Maps `$opaa_logged_uri` und `$opaa_logged_referer` von
`frontend/nginx.conf` steht; `.github/scripts/frontend_hardening_test.sh` prüft am laufenden
Image, dass das Zugriffslog diese Query weder in der Anfragezeile noch im Referer schreibt.

**Grenzen:** Geprüft sind die genannten Wege, nicht jeder Logaufruf; ein Geheimnis in einem neuen
Weg braucht seinen Wächterwert. Die Prüfung sieht das Log im Prozess, nicht das Log von
Datenbank, Proxy oder Modellserver. Ein Header, den ein Client außerhalb von
`RedirectFollowingFetcher` ohne `HeaderValues` setzt, ist nicht geschützt. Die Routenprüfung im
Frontend sieht nur die Parameter `token`, `code` und `state` in der Query, kein Geheimnis im Pfad
(`useParams`) oder im Fragment, und nur Seiten, die `App.tsx` als `<Route path element>` einbindet.

## Angriffsfläche ohne Anmeldung

```bash
# aus backend/: alle Operationen der Spezifikation, alle Handler-Mappings samt Actuator und /mcp
# ohne Token im Profil oidc; nur Einträge von src/test/resources/security/anonymous-allowlist.yaml
# (je mit Begründung) dürfen anders als 401/403/404 antworten, jede Antwort trägt nosniff und
# Frame-Schutz. Dazu die Header des Dokument-Downloads
./gradlew test --tests 'io.opaa.api.AnonymousSurface*' \
  --tests 'io.opaa.library.web.DocumentContentHeadersIntegrationTest'

# aus dem Wurzelverzeichnis: Compose-Vorlagen (alle docker-compose*.yml, .env.docker.example,
# .env.example) binden Ports nur an 127.0.0.1, schalten keine anonyme Systemverwaltung frei und
# tragen keine festen Geheimnisse außer den begründeten Ausnahmen je Dienst im Skript; in CI im Job
# changes (ci.yml), Selbsttest in test_check_compose_defaults.py
.github/scripts/check_compose_defaults.sh
```
