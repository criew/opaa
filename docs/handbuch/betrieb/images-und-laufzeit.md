# Images und Laufzeit

Dieses Kapitel beschreibt die Images von Backend und Frontend: welche Versionen es gibt, wie die
Container zur Laufzeit arbeiten, wie die Plattform sie prüft und wie sie anhalten. Es gilt für jeden
Betriebsweg und gehört zum Teil Betrieb des [Handbuchs](../README.md); die Bausteine einer Installation
beschreibt die [Betriebsübersicht](README.md).

## Images und Versionen

Die CI veröffentlicht fertige Images für `linux/amd64` und `linux/arm64`; auf dem Zielsystem wird
nichts gebaut. Backend und Frontend laufen immer mit **derselben** Version.

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |

- **`X.Y.Z`** ändert sich nie; **`X.Y`** zeigt auf das jüngste Patch-Release der Linie und bringt
  Sicherheitsupdates mit dem nächsten Pull. Ein Tag `latest` gibt es nicht. Im Echtbetrieb läuft ein
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

- **Update-Paket:** Am GitHub-Release hängt ein attestiertes Paket für Datenschutz,
  Personalvertretung und Informationssicherheit
  ([Nachvollziehbarkeit](nachvollziehbarkeit.md#das-update-paket-eines-release)).

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
- **JVM-Optionen** gehen über `JAVA_TOOL_OPTIONS`.

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

<a id="sanftes-herunterfahren"></a>

## Graceful Shutdown

Beim Stopp nimmt das Backend keine neuen Verbindungen an und lässt laufende Anfragen bis
`OPAA_SHUTDOWN_TIMEOUT` zu Ende laufen.

- **Die Grace Period der Plattform muss länger sein** (`OPAA_STOP_GRACE_PERIOD` unter Compose,
  `backend.terminationGracePeriodSeconds` unter Kubernetes), sonst endet der Prozess abrupt.
- **Indexierungs- und Upload-Läufe** der stoppenden Instanz brechen sofort ab und lassen sich
  wiederholen.
- **Mails** liegen bis zur Zustellung in der Datenbank und gehen nicht verloren.
- Bei einem Rolling Update stellt der Helm-Chart die Zeitfenster passend ein
  ([Kubernetes](kubernetes.md#mehrere-backend-instanzen)).

## Weiterführende Kapitel

- [Docker Compose](docker-compose.md) und [Kubernetes](kubernetes.md) — Images holen, Volumes,
  Probes und Grenzen je Betriebsweg
- [Netz und Sicherheit](netz-und-sicherheit.md) — Zugang über Reverse-Proxy und Frontend, Härtung
- [Überwachung und Fehlerbehebung](ueberwachung-und-fehlerbehebung.md) — Logs, Metriken, Diagnose
  ohne Shell
