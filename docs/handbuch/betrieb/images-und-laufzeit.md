# Images und Laufzeit

Dieses Kapitel beschreibt die Images von Backend, Frontend und Texterkennung: welche Versionen es gibt, wie die
Container zur Laufzeit arbeiten, wie die Plattform sie prüft und wie sie anhalten. Es gilt für jeden
Betriebsweg und gehört zum Teil Betrieb des [Handbuchs](../README.md); die Bausteine einer Installation
beschreibt die [Betriebsübersicht](README.md).

## Images und Versionen

Die CI veröffentlicht fertige Images für `linux/amd64` und `linux/arm64`; auf dem Zielsystem wird
nichts gebaut. Backend, Frontend und das Texterkennungs-Image laufen immer mit **derselben** Version.

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-ocr` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |

`opaa-ocr` ist der optionale Dienst für Texterkennung in gescannten Dokumenten
([OCR-Laufzeitimage](#ocr-laufzeitimage)); wer ihn nicht betreibt, braucht das Image nicht.

- **`X.Y.Z`** ändert sich nie; **`X.Y`** steht für die Linie, zeigt auf ihr jüngstes Patch-Release
  und bringt bis zu ihrem Supportende Sicherheitsupdates und Behebungen kritischer Fehler mit dem
  nächsten Pull ([Linien und Supportzeiträume](releases-und-nachweise.md#linien-und-supportzeiträume)). Ein Tag `latest` gibt es nicht. Im Echtbetrieb läuft ein
  Release ([Releases und Nachweise](releases-und-nachweise.md)).
- **`main`** wird bei jedem Push und wöchentlich neu gebaut. Einen festen Stand pinnt nur der
  Digest (`…/opaa-backend@sha256:…`), nicht `sha-<commit>`.
- **Laufende Version:** `/actuator/info` (`build.version`), die Startzeile des Logs und im Frontend
  der Dialog „Info zu …“ im Profilmenü.
- **SBOM und Provenance** hängen je Architektur am Image:

  ```bash
  docker buildx imagetools inspect ghcr.io/criew/opaa-backend:<tag> --format '{{ json .SBOM }}'
  docker buildx imagetools inspect ghcr.io/criew/opaa-backend:<tag> --format '{{ json .Provenance }}'
  ```

- **Signierte Herkunft:** Jedes Image trägt zusätzlich eine signierte Provenance des Laufs, der es
  veröffentlicht hat; sie deckt auch SBOM und Provenance am Image ab. Für ein Release wird sie gegen
  Workflow und Tag geprüft, für `main` mit `--source-ref refs/heads/main`:

  ```bash
  gh attestation verify oci://ghcr.io/criew/opaa-backend:X.Y.Z --repo criew/opaa \
    --signer-workflow criew/opaa/.github/workflows/publish-images.yml --source-ref refs/tags/vX.Y.Z
  ```

  Dasselbe gilt für `opaa-frontend`, `opaa-ocr` und den Chart
  ([Herkunft prüfen](releases-und-nachweise.md#herkunft-prüfen)).

- **Update-Paket:** Am GitHub-Release hängt ein attestiertes Paket für Datenschutz,
  Personalvertretung und Informationssicherheit
  ([Nachvollziehbarkeit](nachvollziehbarkeit.md#das-update-paket-eines-release)).
- **Offline-Bündel:** Am GitHub-Release hängt `opaa-offline-X.Y.Z.tar` für Netze ohne Zugang zu
  GHCR ([Installation ohne Internetzugang](#installation-ohne-internetzugang)).

### Installation ohne Internetzugang

Erreicht das Netz der Installation GHCR nicht, kommt OPAA über das Offline-Bündel hinein. Es
enthält alles von OPAA selbst und lässt sich hinter der Schleuse ohne Netz prüfen.

| Im Bündel | Inhalt |
|---|---|
| `images/` | Backend, Frontend und Texterkennung als OCI-Archiv, je für `linux/amd64` und `linux/arm64`, mit SBOM und Provenance |
| `DIGESTS` | der Digest jedes Image, derselbe wie in GHCR |
| `chart/` | das Helm-Chart |
| `update-paket/` | das Update-Paket, darin dieses Handbuch |
| `sbom/`, `attestations/` | die SBOMs und die Nachweise der Herkunft von Update-Paket, SBOMs, Chart und den drei Images; die Nachweise von Chart und Images beziehen sich auf deren Manifest in der Registry und lassen sich offline nicht gegen die Dateien in `chart/` und `images/` prüfen, die Dateien deckt `SHA256SUMS` ab |
| `SHA256SUMS`, `SHA256SUMS.sigstore.json` | Prüfsummen aller Dateien und ihr Herkunftsnachweis |

Nicht im Bündel sind die Datenbank, der Modellserver, ein Identitätsanbieter und Modellgewichte
der Sprachmodelle. Sie kommen auf eigenem Weg ins Haus. Die Modelle der Texterkennung stecken im
Image `opaa-ocr` und kommen mit ihm.

Mit dem Texterkennungs-Image ist das Bündel mehrere Gigabyte groß. Über der Größengrenze für
Dateien eines GitHub-Release hängt es deshalb in Teilen am Release,
`opaa-offline-X.Y.Z.tar.part-00`, `-01` und so fort; Prüfsumme und Herkunftsnachweis gelten für
das zusammengesetzte Archiv.

**Werkzeuge hinter der Schleuse:** `gh` (die GitHub-CLI, erprobt mit Version 2.86.0), `sha256sum` und
`tar`, `cat` für ein geteiltes Archiv, zum Einspielen `skopeo` und unter Kubernetes `helm`. Sie kommen wie das Bündel durch die
Schleuse. Die Prüfung mit `gh` braucht keine Anmeldung (`gh auth login`) und kein Netz.

**1. Vor der Schleuse**, auf einem Rechner mit Internetzugang, für jedes Release neu:

```bash
gh release download vX.Y.Z --repo criew/opaa --pattern 'opaa-offline-*'
gh attestation trusted-root > trusted_root.jsonl
```

`trusted_root.jsonl` ist der öffentliche Vertrauensanker von Sigstore. Er gehört mit dem Bündel
durch die Schleuse, aber nicht in das Bündel: Wer das Bündel fälschen könnte, könnte sonst auch den
Anker fälschen. Ein Anker von einem früheren Release kann nach einem Schlüsselwechsel bei Sigstore
veraltet sein; die Prüfung scheitert dann, sie wird nicht falsch.

**2. Hinter der Schleuse prüfen**, ohne Netz, zuerst das Archiv, dann seinen Inhalt. Ein geteiltes
Archiv wird vorher zusammengesetzt; die Teile folgen in der Reihenfolge ihrer Namen:

```bash
cat opaa-offline-X.Y.Z.tar.part-* > opaa-offline-X.Y.Z.tar
gh attestation verify opaa-offline-X.Y.Z.tar --bundle opaa-offline-X.Y.Z.tar.sigstore.json \
  --custom-trusted-root trusted_root.jsonl --repo criew/opaa \
  --signer-workflow criew/opaa/.github/workflows/offline-bundle.yml \
  --source-ref refs/tags/vX.Y.Z
tar -xf opaa-offline-X.Y.Z.tar
cd opaa-offline-X.Y.Z
gh attestation verify SHA256SUMS --bundle SHA256SUMS.sigstore.json \
  --custom-trusted-root ../trusted_root.jsonl --repo criew/opaa \
  --signer-workflow criew/opaa/.github/workflows/offline-bundle.yml \
  --source-ref refs/tags/vX.Y.Z
sha256sum -c SHA256SUMS
```

Endet die erste Prüfung ohne Fehler, stammt das Archiv aus dem Release-Lauf zu `vX.Y.Z`: Auf einem
Release-Tag erzeugt nur dieser Lauf ein Bündel, und nur das Bündel seiner eigenen Version. Die zweite
Prüfung bindet `SHA256SUMS` ebenso an den Lauf, `sha256sum -c` danach jede dort genannte Datei; ein
verändertes Image-Archiv fällt in diesem Schritt auf. `opaa-offline-X.Y.Z.tar.sha256` neben dem
Archiv belegt nur eine heile Übertragung, keine Herkunft. Die Befehle stehen hier und nicht nur im
Bündel: Eine Anleitung aus dem Bündel ist vor der Prüfung so ungeprüft wie das Bündel selbst.

Die Herkunft des Update-Pakets und der SBOMs lässt sich zusätzlich prüfen, etwa:

```bash
gh attestation verify update-paket/opaa-update-paket-X.Y.Z.tar.gz \
  --bundle attestations/opaa-update-paket-X.Y.Z.tar.gz.jsonl \
  --custom-trusted-root ../trusted_root.jsonl --repo criew/opaa \
  --signer-workflow criew/opaa/.github/workflows/publish-images.yml \
  --source-ref refs/tags/vX.Y.Z
```

**3. In die interne Registry einspielen.** `skopeo` übernimmt beide Architekturen und behält die
Digests:

```bash
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-backend-X.Y.Z.oci.tar docker://registry.intern/opaa/opaa-backend:X.Y.Z
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-frontend-X.Y.Z.oci.tar docker://registry.intern/opaa/opaa-frontend:X.Y.Z
skopeo copy --all --preserve-digests \
  oci-archive:images/opaa-ocr-X.Y.Z.oci.tar docker://registry.intern/opaa/opaa-ocr:X.Y.Z
```

Ohne Registry, etwa für Docker Compose auf einem Host, lädt
`skopeo copy oci-archive:images/opaa-backend-X.Y.Z.oci.tar docker-daemon:ghcr.io/criew/opaa-backend:X.Y.Z`
das Image der eigenen Architektur in den lokalen Docker; ebenso Frontend und Texterkennung.

**4. Installieren:** unter Kubernetes mit dem Chart aus dem Bündel und den Image-Adressen der
internen Registry ([Kubernetes](kubernetes.md#installation-ohne-internetzugang)), unter Docker
Compose wie gewohnt mit den geladenen Images.

## Backend-Laufzeitimage

Java 21 auf einem Distroless-Image, ohne Shell, Coreutils, `curl` und Paketmanager.

- **Keine Shell:** `docker exec … sh` scheitert; Diagnose über Log, Actuator und Werkzeug-Container
  ([Diagnose ohne Shell](ueberwachung-und-fehlerbehebung.md#diagnose-ohne-shell)). Health Checks
  fragen von außen ab.
- **Ohne Root:** Benutzer und Gruppe `65532`, Port 8080. Eingehängte Verzeichnisse müssen für diese
  Kennung zugänglich sein.
- **Zertifikate:** Eine interne CA kommt in eine **Kopie** des mitgelieferten Truststores, nie in
  einen leeren Store — sonst scheitert HTTPS zu allen öffentlichen Diensten
  ([Docker Compose](docker-compose.md#eigene-interne-ca-ergänzen),
  [Kubernetes](kubernetes.md#eigene-zertifizierungsstelle)).
- **JVM-Optionen** gehen über `JAVA_TOOL_OPTIONS`. Nur eine Option, die schon vor der Anwendung einen
  Hostnamen auflöst, verhindert den Start: JMX-Remote (`-Dcom.sun.management.jmxremote.*`),
  `-Djdk.net.hosts.file` und ein `-javaagent`, der beim Laden Namen auflöst. Speicheroptionen und JFR
  sind unschädlich. OPAA muss die Namensauflösung der JVM selbst übernehmen, um geprüfte Ziele zu
  binden ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)); die Meldung beim
  Abbruch nennt diese Ursache.

<a id="nur-lesbares-dateisystem"></a>

### Read-only Filesystem

Das Backend schreibt nur unter `/tmp` (Arbeitsdateien, Uploads, heruntergeladene Quelldateien) und,
solange die Originale im Dateisystem liegen, unter `OPAA_UPLOAD_STORAGE_PATH`. Eigene
Temp-Verzeichnisse für S3 (`OPAA_UPLOAD_S3_TEMP_DIRECTORY`, `OPAA_INDEXING_S3_TEMP_DIRECTORY`)
liegen unter `/tmp` oder auf einem eigenen Volume.

- `/tmp` liegt auf einem Datenträger und fasst mehrere der größten Dokumente gleichzeitig, bei
  vorgesehenem Heap-Dump zusätzlich die Heap-Größe.
- Heap-Dumps und JFR-Mitschnitte gehören absolut unter `/tmp`, sonst `Read-only file system`.
- **Sicherheit:** Reste in `/tmp`, Heap-Dumps und JFR-Mitschnitte können Inhalte privater
  Bibliotheken, Geheimnisse und entschlüsselte Zugangsdaten enthalten. Nach der Diagnose löschen.

### Speicher und Container-Grenzen

Ohne Angabe nimmt die JVM 25 % des sichtbaren Speichers als Heap. Mit Container-Limit wird der
Anteil angehoben, ohne Limit nicht.

| | Request | Limit | Heap-Anteil |
|---|---|---|---|
| Speicher | 1 GiB | 2 GiB | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` |
| CPU | 0,5 | keine | — |

Große Bestände, sehr große PDF-Dateien oder viele gleichzeitige Indexierungen brauchen mehr. Ein
OOM-Kill zeigt sich als `OOMKilled` (Kubernetes) bzw. in
`docker inspect --format '{{.State.OOMKilled}}' <container>`; dann das Limit anheben, nicht den
Heap-Anteil. Exit-Code `137` allein ist nicht eindeutig.

Reicht der Heap selbst nicht, beendet sich das Backend sofort (`-XX:+ExitOnOutOfMemoryError` im
Image, Exit-Code `3`, im Log `Terminating due to java.lang.OutOfMemoryError`), statt mit
beschädigten Warteschlangen und Sperren weiterzulaufen. Docker Compose (`restart: unless-stopped`)
und Kubernetes starten es neu. Wiederholt sich das bei derselben Datei, liegt meist ein sehr großes
Dokument vor; dann den Heap vergrößern oder die
[Grenzen beim Lesen](../referenz/formate/README.md#grenzen-beim-lesen)
senken.

## Frontend-Laufzeitimage

nginx (`nginxinc/nginx-unprivileged`), lauffähig unter Pod Security Standard `restricted`.

- **Ohne Root:** Benutzer `101`, Port 8080, auch mit beliebiger, vom Cluster vergebener Kennung.
- **Read-only Filesystem:** schreibt nur unter `/tmp` (Konfiguration, Zwischenablage für Uploads und
  große Antworten). Bei vielen großen Uploads gleichzeitig `/tmp` vergrößern.
- **`OPAA_BACKEND_UPSTREAM`:** Host und Port des Backends.
- **`OPAA_BACKEND_READ_TIMEOUT`:** wie lange nginx auf das Backend wartet. Eine Chat-Antwort kann
  Minuten brauchen; zu kurz endet mit `504`.
- **Uploadgröße:** fest 52 MB, knapp über der Grenze des Backends (`OPAA_UPLOAD_MAX_FILE_SIZE`). Wer
  diese anhebt, passt das Image und jeden Proxy davor an.
- Ungültige Werte dieser Variablen brechen den Containerstart mit einer Meldung im Log ab.

## OCR-Laufzeitimage

`opaa-ocr` liest Text aus gescannten Seiten: ein Docling-Dienst (docling-serve) für die CPU mit
der Texterkennung RapidOCR und den deutschen PP-OCRv6-Modellen. Alle Modelle stecken im Image; der
Dienst lädt nichts nach und läuft ohne Netzzugang. Ein Backend erreicht ihn im internen Netz.

- **Schlüssel ist Pflicht:** `DOCLING_SERVE_API_KEY` mit mindestens 32 Zeichen ohne Leerzeichen,
  etwa aus `openssl rand -hex 32`. Ohne ihn oder mit einem kürzeren startet der Container nicht
  (Exit-Code `64`, die Meldung nennt die Variable). Jede Anfrage trägt ihn im Header `X-Api-Key`;
  ohne ihn antwortet der Dienst mit `401`.
- **Ohne Root:** Benutzer `1001`, Port `5001`.
- **Read-only Filesystem:** schreibt nur unter `/tmp` (Arbeitsdateien und Zwischenspeicher der
  Bibliotheken). `/tmp` muss ausführbar eingehängt sein und eine gescannte Seite als Bild fassen;
  einige Gigabyte genügen.
- **Ohne Netz:** Der Dienst braucht keinen Ausgang; eine Netzrichtlinie darf jeden Egress sperren.
- **Nur RapidOCR:** Eine Anfrage wählt keine andere Texterkennung und keine eigenen Modelle; andere
  Voreinstellungen weist der Dienst mit `422` ab. Sprache und Texterkennung der ganzen Seite gibt
  jede Anfrage selbst an.
- **Ressourcen:** Die Modelle lädt der Dienst beim Start; bereit ist er nach Sekunden bis wenigen
  Minuten, je nach Datenträger. Eine Seite braucht auf der CPU einige Sekunden. Nach einer
  gescannten Seite belegt der Container rund 2,3 GiB Speicher; große Dokumente brauchen mehr.
  Ausgangswerte:

  | | Request | Limit |
  |---|---|---|
  | Speicher | 3 GiB | 4 GiB |
  | CPU | 1 | keine |

  Mehr Kerne verkürzen die Umwandlung. Ein `OOMKilled` verlangt ein höheres Limit. Das Image ist
  entpackt rund 2,2 GB (`linux/amd64`) bzw. 4,7 GB (`linux/arm64`) groß.
- **Readiness:** `GET /ready` meldet `200`, sobald die Modelle geladen sind; `GET /health` prüft nur
  den Prozess. Beide brauchen keinen Schlüssel. Das Image bringt einen Health Check auf `/ready` mit.

<a id="bereitschaft-und-lebendigkeit"></a>

## Readiness und Liveness

| Pfad | Prüft | Gedacht für |
| --- | --- | --- |
| `GET /actuator/health/readiness` | Prozess und Datenbank | Readiness-Probe, Health Check, Load Balancer |
| `GET /actuator/health/liveness` | Prozess | Liveness-Probe (Neustart) |
| `GET /actuator/health` | Überblick | Diagnose |

- Ohne Anmeldung antworten sie nur mit `UP`/`DOWN`; Details und alle weiteren Health Groups
  verlangen ein Zugangstoken.
- Die Readiness braucht eine Datenbankverbindung: Auch ein erschöpfter Connection Pool meldet `DOWN`.
  Bei knappen Pools die Probe nachsichtig konfigurieren.
- Modelldienste stehen bewusst nicht in der Readiness; ein hängender Modellserver nimmt die Instanz
  nicht aus der Rotation. Sie haben eigene Health Groups:

| Health Group | Prüft |
| --- | --- |
| `chat-model` | welches Chat-Modell aktiv ist, ohne Aufruf |
| `embedding-model` | einen echten Embedding-Aufruf |
| `transcription-model` | letzten bekannten Zustand der Spracherkennung |
| `vector-store` | eine echte Ähnlichkeitssuche |
| `upload-store` | den S3-Objektspeicher der Originalablage |
| `mail` | den letzten SMTP-Versand |

Ohne eingerichtetes Chat- oder Embedding-Modell melden `chat-model`, `embedding-model` und
`vector-store` `UNKNOWN` mit dem Detail `NOT_CONFIGURED`; das ist ein regulärer Zustand einer
Installation, die ihre Modelle noch nicht festgelegt hat ([Installation](installation.md#ohne-modelle-installiert)).

<a id="sanftes-herunterfahren"></a>

## Graceful Shutdown

Beim Stopp nimmt das Backend keine neuen Verbindungen an und lässt laufende Anfragen bis
`OPAA_SHUTDOWN_TIMEOUT` zu Ende laufen.

- **Die Grace Period der Plattform muss länger sein** (`OPAA_STOP_GRACE_PERIOD` unter Compose,
  `backend.terminationGracePeriodSeconds` unter Kubernetes), sonst endet der Prozess abrupt.
- **Indexierungs- und Upload-Läufe** der stoppenden Instanz brechen sofort ab und lassen sich
  wiederholen.
- **Antworten, die gerade in den Chat strömen,** bricht die stoppende Instanz sofort ab, statt auf
  das Modell zu warten. Die Person sieht einen Hinweis, die Runde wird nicht gespeichert, und die
  Frage steht wieder im Eingabefeld.
- **Mails** liegen bis zur Zustellung in der Datenbank und gehen nicht verloren.
- Bei einem Rolling Update stellt der Helm-Chart die Zeitfenster passend ein
  ([Kubernetes](kubernetes.md#mehrere-backend-instanzen)).
