# Betriebsübersicht

## Deployment aus vorgebauten Images (GHCR)

Für Zielsysteme, auf denen nicht aus dem Quellcode gebaut werden soll, veröffentlicht CI fertige
Container-Images. Es gibt zwei Arten von Ständen:

- **Releases** mit fester Versionsnummer. Ein Release wird einmal gebaut und nie überschrieben.
- **Der Entwicklungsstand `main`.** Er wird bei jedem Push auf `main` und zusätzlich wöchentlich
  (montags) neu gebaut, damit er auch ohne Codeänderung die aktuellen OS-Sicherheitsupdates trägt.

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `X.Y.Z`, `X.Y`, `main`, `sha-<commit>` |

Jedes Release und der aktuelle Stand `main` liegen als Manifest-Index mit je einem Image für
`linux/amd64` und `linux/arm64` vor. Docker und Kubernetes ziehen die Variante, die zur Architektur
des Hosts passt; auf ARM-Servern und Apple-Silicon-Rechnern läuft OPAA damit ohne Emulation.
Ältere `sha-<commit>`-Tags aus der Zeit, bevor arm64 gebaut wurde, gibt es nur für `linux/amd64`.

`X.Y.Z` ist eine Release-Version und ändert ihren Inhalt nie. `X.Y` zeigt auf das jüngste
Patch-Release dieser Linie. Die verfügbaren Versionen und ihre Änderungen stehen unter *Releases*
im GitHub-Repository. Die Notizen jedes Release enthalten den Abschnitt „Vorbereitung für
Bestandsinstallationen“: Er nennt die Schritte, die eine Bestandsinstallation vor dem Update
erledigen muss, oder sagt „Keine Vorbereitung nötig.“ Wer Versionen überspringt, prüft diesen
Abschnitt bei jedem übersprungenen Release. Ein Tag `latest` gibt es nicht.

Für einen Betrieb, der Aktualisierungen plant, ist eine Release-Version der empfohlene Stand. Ein
Release bekommt Sicherheitsupdates des Basis-Images nicht durch den wöchentlichen Neubau, sondern
über ein Patch-Release. Wer `X.Y` verwendet, erhält Patch-Releases mit dem nächsten Pull. Wer
`X.Y.Z` pinnt, wechselt bewusst auf die neue Version.

`main` folgt dem jeweils letzten Stand. Ein Neubau eines inzwischen überholten Commits setzt `main`
nicht zurück, sondern nur sein `sha-<commit>`. Eine Release-Version `X.Y.Z` ist bereits reproduzierbar.
Wer `main` betreibt und trotzdem einen festen Stand braucht, **pinnt den Digest**, nicht einen Tag:

```yaml
image: ghcr.io/criew/opaa-backend@sha256:…
```

Auch `sha-<commit>` taugt dafür nicht: Der wöchentliche Neubau veröffentlicht denselben Commit erneut
und verschiebt `main` **und** `sha-<commit>` gemeinsam auf den neuen Digest, sofern der Commit noch
der Stand von `main` ist; sonst nur `sha-<commit>`. `sha-<commit>` beantwortet
also „aus welchem Commit“, nicht „welches Image“. Den Digest eines laufenden Stands liefert
`docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main`.

Jedes Image trägt je Architektur eine SBOM- und eine Provenance-Attestierung, die beim Bauen erzeugt
werden; die SBOM deckt auch die Betriebssystempakete des Basis-Images ab. Abruf, nach Architektur
gegliedert:

```bash
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .Provenance }}'
# nur eine Architektur
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json (index .SBOM "linux/arm64") }}'
```

(für das Frontend entsprechend mit `opaa-frontend`). Zusätzlich legt die CI bei jedem Push auf
`main` je eine CycloneDX-SBOM der ausgelieferten Laufzeit-Abhängigkeiten von Backend und Frontend
als Workflow-Artefakte `sbom-backend` und `sbom-frontend` ab (90 Tage Aufbewahrung, ohne Test- und
Build-Werkzeuge).

## Vor der Inbetriebnahme: mandantenfähiger Betrieb

Eine Installation wird mit **genau einer** Organisation ausgeliefert; es gibt keinen Verwaltungspfad, der eine zweite anlegt, und alle Identitätsanbieter provisionieren Konten in dieselbe Organisation. Solange das so bleibt, ist die Mandantengrenze unkritisch — es gibt nichts, was sie überschreiten könnte.

Die Grenze wird dennoch in drei Schichten gehalten, damit eine spätere zweite Organisation keine nachträgliche Umstellung jeder Rechteabfrage verlangt: Die **Anwendung** löst fremde Kennungen nur innerhalb der Organisation des Aufrufenden auf (ein Objekt einer anderen Organisation ist „nicht gefunden", nicht „nicht erlaubt"); die **Datenbank** führt die Organisation in jedem Verweis auf ein organisationsgebundenes Objekt mit, sodass eine Zeile, die zwei Organisationen mischt, nicht anlegbar ist; ein **struktureller Prüflauf** in der Testsuite liest das tatsächliche Schema aus und schlägt an, sobald eine neue Tabelle diesen Aufbau verletzt. Außerhalb dieses Prüflaufs liegen die Historientabellen der Rechtevergabe (absichtlich ohne Fremdschlüssel, damit das Löschen einer Bibliothek ihre Historie nicht mitreißt) und die Vektortabelle, die die Organisation nur als JSON-Metadatum trägt.

## Backend-Laufzeitimage

Das Backend läuft in einem bewusst minimalen Image: eine mit `jlink` erzeugte Java-21-Laufzeit auf
einem Distroless-Basisimage. Außer der C-Bibliothek enthält das Image kein Betriebssystempaket —
keine Shell, keine Coreutils, kein `curl`/`wget`, keinen Paketmanager. Für den Betrieb folgt daraus:

- **`docker exec … sh` funktioniert nicht.** Es gibt keine ausführbare Shell im Container.
- **Ein `healthcheck:` in der `docker-compose.yml` darf für den Backend-Service kein `CMD-SHELL`
  und kein `curl`/`wget` verwenden.** Der mitgelieferte Stack definiert für das Backend keinen
  Healthcheck; wer einen braucht, prüft `GET /actuator/health/readiness` von außen (siehe
  [„Bereitschaft und Lebendigkeit"](#bereitschaft-und-lebendigkeit)) statt aus dem Container
  heraus.
- **Zeitzone:** Der Container läuft in UTC, solange `TZ` nicht gesetzt ist. `TZ=Europe/Berlin` wirkt
  wie gewohnt — die Zeitzonendatenbank steckt in der Java-Laufzeit, nicht in einem OS-Paket.
- **Zertifikate:** Der Truststore der Anwendung ist der `cacerts` der Java-Laufzeit mit den
  öffentlichen CAs. `update-ca-certificates` gibt es im Image nicht mehr; eine eigene interne CA
  wird stattdessen in eine **Kopie dieses Truststores** aufgenommen (siehe unten) — nie in einen
  leeren, neuen Store.
- **Zusätzliche JVM-Optionen** (Heap-Grenzen, Debug-Agent, JFR) werden über `JAVA_TOOL_OPTIONS`
  gesetzt; ein `java`-Aufruf mit eigenen Argumenten ist nicht nötig und würde den Entrypoint
  überschreiben.
- **Der Prozess läuft ohne Sonderrechte** unter der Kennung `65532` (siehe unten). Das betrifft die
  Eigentümerverhältnisse der eingehängten Verzeichnisse.
- **Das Root-Dateisystem darf nur lesbar sein.** Siehe
  [„Nur lesbares Dateisystem"](#nur-lesbares-dateisystem) unten.
- **Die Heap-Grenze richtet sich nach der Speichergrenze des Containers.** Siehe
  [„Speicher und Container-Grenzen"](#speicher-und-container-grenzen) unten.

### Nur lesbares Dateisystem

Das Backend schreibt an genau zwei Stellen:

| Pfad | Inhalt | Nötig |
|---|---|---|
| `/tmp` (`java.io.tmpdir`) | Arbeitsdateien von Tomcat und Uploads, heruntergeladene Quelldateien, Arbeitskopien für den Objektspeicher, der Font-Cache von PDFBox, Laufzeitdaten der JVM | immer |
| `/app/uploads` (`OPAA_UPLOAD_STORAGE_PATH`) | die hochgeladenen Originale | nur, solange sie im Dateisystem liegen (`OPAA_UPLOAD_STORE` nicht auf `s3`) |

Alles andere liest der Prozess nur. Ein eigenes `OPAA_UPLOAD_S3_TEMP_DIRECTORY` oder
`OPAA_INDEXING_S3_TEMP_DIRECTORY` kommt als dritter beschreibbarer Pfad hinzu.

Diagnoseziele gehören deshalb immer absolut unter `/tmp`: Ein `jcmd … GC.heap_dump heap.hprof`, ein
`JFR.dump` ohne `filename` und `-XX:+HeapDumpOnOutOfMemoryError` ohne `-XX:HeapDumpPath=/tmp`
schreiben sonst ins Arbeitsverzeichnis `/app` und scheitern mit `Read-only file system`.

Der Compose-Stack startet das Backend deshalb mit `read_only: true` und `no-new-privileges`; `/tmp`
ist dort das benannte Volume `opaa-backend-tmp`. Ein Volume statt `tmpfs`, weil die Dateien dort die
Größe eines Dokuments erreichen und keinen Arbeitsspeicher belegen sollen.

**Das Volume überlebt Neuerstellung und `docker compose down`.** Was ein geordneter Stopp nicht
selbst entfernt, bleibt liegen: Arbeitsdateien eines harten Abbruchs, etwa nach einem Abbruch wegen
der Speichergrenze, und jeder Heap-Dump oder JFR-Mitschnitt. Diese Dateien können Inhalte privater
Bibliotheken enthalten, ein Heap-Dump zusätzlich Geheimnisse und entschlüsselte Zugangsdaten.
Aufgeräumt wird bei angehaltenem Backend:

```bash
docker compose stop backend && docker compose rm -f backend
docker volume rm <projekt>_opaa-backend-tmp
docker compose up -d backend
```

Unter Kubernetes gehört ein `emptyDir` ohne `medium: Memory` nach `/tmp`, mit einem `sizeLimit`, das
mehrere der größten erwarteten Dokumente gleichzeitig fasst, und einen Heap-Dump, wenn einer
vorgesehen ist: Er wird so groß wie der Heap. Überschreitet das Verzeichnis das `sizeLimit`, verdrängt
das Kubelet den Pod. Ein `emptyDir` verschwindet mit dem Pod. Der Helm-Chart legt es an
(`backend.tmp`, siehe [Kubernetes](kubernetes.md#pod-security)).

Der Pfad `/app/uploads` ist im Image als Vorgabe von `OPAA_UPLOAD_STORAGE_PATH` gesetzt. Das ist die
absolute Form der Anwendungsvorgabe `./uploads`.

### Speicher und Container-Grenzen

Die JVM richtet ihre Heap-Grenze nach dem Speicher, den sie sieht. Ohne eigene Angabe nimmt sie
**25 %** davon: ohne Container-Grenze 25 % des Host-Speichers, mit Grenze 25 % der Grenze. Mit einer
Grenze bleibt so viel Speicher ungenutzt, deshalb wird der Anteil dann über `JAVA_TOOL_OPTIONS`
angehoben:

```text
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70
```

Der Rest der Grenze gehört nicht dem Heap, sondern Klassen, übersetztem Code, Threads und Puffern.
Ohne Container-Grenze wird der Anteil **nicht** angehoben, sonst konkurriert die JVM mit Datenbank
und Modellbetrieb um den Host-Speicher.

**Messwert als Ausgangspunkt.** Die E2E-Suite (Uploads, Indexierung von PDF-, Office- und
Mail-Dateien, Chat) lief mit einer Grenze von 1,5 GiB und 75 % Heap ohne Abbruch:

- Spitze des Containers 1,0 GiB
- belegter Heap höchstens rund 400 MiB
- Klassen, Code und Threads zusammen rund 420 MiB

Für den Betrieb empfohlen:

| | Anforderung (`requests`) | Grenze (`limits`) | Heap-Anteil |
|---|---|---|---|
| Speicher | 1 GiB | 2 GiB | `-XX:MaxRAMPercentage=70` |
| CPU | 0,5 | keine | — |

Die CPU-Werte sind nicht gemessen. Eine CPU-Grenze drosselt die JVM gerade beim Start und bei der
Indexierung, deshalb wird sie nur gesetzt, wenn der Cluster sie verlangt.

Große Bestände, sehr große PDF-Dateien oder viele gleichzeitige Indexierungen brauchen mehr. Ein
Abbruch wegen der Grenze zeigt sich unter Kubernetes als `OOMKilled` im Status des Pods, unter
Compose als `true` in `docker inspect --format '{{.State.OOMKilled}}' <container>`. Der Exit-Code
`137` allein ist nicht eindeutig, er entsteht auch nach Ablauf von `stop_grace_period`. Steht dort
`true`, die Grenze anheben, nicht den Heap-Anteil.

## Frontend-Laufzeitimage

Das Frontend liefert die Oberfläche aus und reicht `/api/` und `/mcp` an das Backend weiter. Es
läuft auf dem unprivilegierten nginx-Image (`nginxinc/nginx-unprivileged`) und hält dieselben
Einschränkungen ein, die ein Kubernetes-Cluster mit Pod Security Standard `restricted` verlangt:

- **Ohne root:** Der Prozess läuft als Benutzer `101` und lauscht auf **Port 8080**. Der Compose-Stack
  bildet den Host-Port `OPAA_FRONTEND_PORT` darauf ab.
- **IPv4 und IPv6:** nginx lauscht auf Port 8080 über IPv4 und, wenn der Container IPv6 hat, auch
  über IPv6. Ein Healthcheck oder eine Probe kann deshalb `localhost` verwenden, und in IPv6-only-
  oder Dual-Stack-Clustern ist das Frontend über beide Adressfamilien erreichbar. Ob IPv6 vorhanden
  ist, entscheidet der Container beim Start anhand von `/proc/net/if_inet6`. Fehlt die Datei, weil
  der Kernel ohne IPv6 läuft, lauscht nginx nur über IPv4 und meldet das im Protokoll.
- **Nur lesbares Dateisystem:** Beim Start schreibt der Container ausschließlich unter `/tmp`. Dort
  liegen die erzeugte Serverkonfiguration (`/tmp/nginx/conf.d/`, `/tmp/nginx/listen.d/`), die
  PID-Datei und die Zwischenablage für Uploads. Der Compose-Stack startet den Dienst deshalb mit
  `read_only: true`, `/tmp` als `tmpfs`, ohne Capabilities und mit `no-new-privileges`. Unter
  Kubernetes gehört ein `emptyDir` nach `/tmp`.
- **`/tmp` ist unter Compose Arbeitsspeicher:** Das `tmpfs` ist auf 512 MiB begrenzt. Dort puffert
  nginx Uploads und, bei langsamen Clients, große Antworten des Backends. Je Antwort ist der
  Puffer begrenzt, darüber liefert nginx im Takt des Clients aus. Reicht der Platz bei vielen
  gleichzeitigen großen Uploads nicht, wird die Grenze in der eigenen Compose-Datei angehoben.
- **Beliebige Kennung:** Das Image läuft auch unter einer vom Cluster zugewiesenen Kennung, etwa
  bei OpenShift, solange `/tmp` beschreibbar ist.
- **Backend-Ziel einstellbar:** `OPAA_BACKEND_UPSTREAM` nennt Host und Port des Backends. Ein Wert,
  der nicht die Form `host[:port]` hat (Port 1 bis 65535), bricht den Start mit einer Meldung im
  Protokoll ab, statt in die nginx-Konfiguration zu gelangen.
- **Wartezeit auf das Backend einstellbar:** `OPAA_BACKEND_READ_TIMEOUT` bestimmt, wie lange nginx
  auf die Antwort des Backends wartet, für `/api/` und `/mcp` gleichermaßen. Eine Chat-Antwort kommt
  erst, wenn Suche, Reranking und Modellaufruf fertig sind, und kann Minuten brauchen. Antwortet
  das Backend nicht rechtzeitig, beendet nginx die Anfrage mit `504`. Ein Wert ohne Einheit `s`, `m`
  oder `h` bricht den Start mit einer Meldung im Protokoll ab.

**Bestehende Installation.** Mit der `docker-compose.yml` aus diesem Repository ändert sich nichts:
Sie bildet den Host-Port bereits auf `8080` ab. Eine eigene Compose-Datei und ein eigener Proxy im
Docker-Netz müssen umgestellt werden, siehe Punkt 6 der
[Vorbereitungsschritte](#vorbereitungsschritte-für-bestandsinstallationen).

## Bereitschaft und Lebendigkeit

Für einen Container-Healthcheck oder die Probe eines Lastverteilers ist nicht der Gesamtstatus der
richtige Pfad, sondern:

| Pfad | Antwortet auf Basis von | Gedacht für |
| --- | --- | --- |
| `GET /actuator/health/readiness` | Prozesszustand und Datenbank | Lastverteiler-Probe, Container-Healthcheck: Darf diese Instanz Anfragen bekommen? |
| `GET /actuator/health/liveness` | Prozesszustand | Neustart-Entscheidung: Lebt der Prozess noch? |
| `GET /actuator/health` | alles, was nicht in einer eigenen Gruppe steht (siehe unten) | Überblick bei der Diagnose |

Beide Proben sind ohne Anmeldung erreichbar und antworten dann nur mit dem Status, ohne
Aufschlüsselung — ebenso der Gesamtstatus. Jede **andere** Gruppe (Tabelle weiter unten) verlangt
dagegen ein gültiges Zugangstoken: Zwei von ihnen lösen bei jedem Abruf einen echten Modellaufruf
bzw. eine echte Ähnlichkeitssuche aus, und das darf niemand ohne Anmeldung auslösen können.

> **Die Bereitschaft zieht eine Datenbankverbindung.** Das ist beabsichtigt, hat aber einen
> Grenzfall: Nicht nur ein Ausfall der Datenbank lässt die Probe `DOWN` melden, sondern auch ein
> erschöpfter Verbindungspool. Eine Instanz, die unter Last alle Verbindungen in langen
> Chat-Anfragen hält, kann sich so selbst aus der Rotation nehmen, obwohl sie arbeitet. Wer sehr
> knapp dimensionierte Pools fährt, sollte die Probe deshalb mit einer nachsichtigen Anzahl
> Fehlversuche konfigurieren, statt beim ersten `DOWN` umzuschalten.

**Was bewusst nicht in der Bereitschaft steht:** Chat-Modell, Embedding-Modell und Vektorspeicher.
Jeder dieser drei Zustände hängt an einem fremden Dienst, und ein hängendes Ollama darf eine Instanz
nicht aus der Rotation nehmen, während Anmeldung, Bibliotheksverwaltung und Dokumentenzugriff
weiterarbeiten. Ohne Datenbank dagegen kann die Instanz gar nichts beantworten — sie gehört deshalb
dazu.

Dieselben drei stehen aus demselben Grund auch nicht im Gesamtstatus, sondern in je einer eigenen
Gruppe. Ein Abruf des Gesamtstatus löst damit auch keinen Modellaufruf mehr aus — nur der Abruf der
betreffenden Gruppe tut das:

| Gruppe | Prüft |
| --- | --- |
| `GET /actuator/health/chat-model` | Welches Chat-Modell aktiv ist (ohne Aufruf des Modells) |
| `GET /actuator/health/embedding-model` | Einen echten Embedding-Aufruf |
| `GET /actuator/health/vector-store` | Eine echte Ähnlichkeitssuche |
| `GET /actuator/health/upload-store` | Den Objektspeicher der Originalablage (nur mit `OPAA_UPLOAD_STORE=s3`) |
| `GET /actuator/health/mail` | Den letzten SMTP-Versand |

Mit `OPAA_UPLOAD_STORE=s3` antwortet `GET /actuator/health/upload-store` `UP` mit
Endpunkt und Bucket oder `DOWN` mit dem Grund (siehe `OPAA_UPLOAD_STORE` in der Tabelle unten).
`GET /actuator/health/mail` antwortet `UP`, `DOWN` oder `UNKNOWN` — ohne Einzelheiten, die Ursache
steht auf der Einstellungsseite und im Protokoll (siehe [„E-Mail-Versand
(SMTP)"](#e-mail-versand-smtp)).

## Sanftes Herunterfahren

Ein Stopp des Containers bricht laufende HTTP-Anfragen nicht mehr ab: Das Backend nimmt keine neuen
Verbindungen mehr an und lässt die bereits angenommenen Anfragen zu Ende laufen — höchstens so
lange, wie `OPAA_SHUTDOWN_TIMEOUT` erlaubt (siehe Variablenliste unten). Erst danach werden sie
abgebrochen. Zusammen mit den beiden Proben oben ist das die Voraussetzung dafür, eine
Instanz hinter einem Lastverteiler ohne Ausfall zu erneuern: aus der Rotation nehmen, laufende
Anfragen auslaufen lassen, stoppen.

Hintergrundläufe teilen dieses Zeitfenster **nicht**. Ein laufender Indizierungs- oder
Upload-Vorgang wird beim Stopp sofort abgebrochen und verzögert ihn um nichts; sein Auftrag wird
beim nächsten Start als abgebrochen erkannt und kann wiederholt werden. Ein großer Indizierungslauf
kann einen Neustart also nicht hinhalten.

Zwei Hintergrundarbeiten bekommen dagegen ein eigenes, kurzes und fest eingebautes Zeitfenster: die
Erzeugung des Chat-Titels und die Verdichtung der Gesprächsnotiz. Beide werden nie nachgeholt — ein
abgebrochener Titel bleibt der Vorgabetext —, deshalb bekommt ein bereits laufender Aufruf noch
einen Moment. Ein langsamer Aufruf wird trotzdem abgebrochen; das Fenster ist eine Kulanz, keine
Zusage. Es kommt zum Zeitfenster der Anfragen hinzu: `OPAA_SHUTDOWN_TIMEOUT` gilt je Stopp-Phase,
nicht als Gesamtbudget des Stopps.

> **Wer das Zeitfenster anhebt, hebt auch `stop_grace_period` an.** Docker beendet einen Container
> nach Ablauf dieser Frist hart (`SIGKILL`); die mitgelieferte `docker-compose.yml` setzt sie für
> das Backend über `OPAA_STOP_GRACE_PERIOD` etwas über das Zeitfenster hinaus. Ist sie kürzer als
> `OPAA_SHUTDOWN_TIMEOUT`, endet der Prozess doch abrupt und das sanfte Herunterfahren läuft ins
> Leere.

## Diagnose ohne Shell

```bash
curl -s http://localhost:8081/actuator/info          # Version des Backends
curl -s http://localhost:8081/actuator/health        # Zustandsübersicht
curl -s http://localhost:8081/actuator/health/readiness   # Bereitschaft (Lastverteiler-Probe)
curl -s http://localhost:8081/actuator/health/liveness    # Lebendigkeit des Prozesses
curl -s http://localhost:8081/actuator/metrics       # Metriken
curl -s http://localhost:8081/actuator/prometheus    # Prometheus-Format
curl -s -H "Authorization: Bearer <token>" https://<host>/api/v1/admin/diagnostics/client-address   # aufgelöste Client-Adresse dieser Anfrage
```

Welche Version läuft, nennen `/actuator/info` unter `build.version` und die Startzeile des
Protokolls (`Starting OpaaApplication v<Version>`), für das Frontend der Dialog „Info zu …“ im
Profilmenü. Ein Release-Image meldet die Version seines Tags, ein Image von `main` den
Entwicklungsstand `0.0.0-dev`.

Der letzte Aufruf (nur Systemverwaltung, mit dem Zugangstoken einer angemeldeten Sitzung und über
denselben Weg, den auch die Browser nehmen — also durch den Proxy) zeigt, wie das Backend die
Adresse **genau dieser** Anfrage auflöst: `remoteAddress` ist die Adresse der Verbindung (hinter
einem Proxy dessen Adresse), `forwardedFor` der empfangene `X-Forwarded-For`-Header,
`forwardedForTrusted`, ob er gezählt hat, und `clientAddress` die Adresse, nach der Rate-Limits und
`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` diese Anfrage beurteilen. Steht dort die Adresse des Proxys statt
die des eigenen Rechners, fehlt die Proxy-Adresse in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` (siehe
[„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments)).

Wie viel `/actuator/health` zeigt, hängt von der Authentifizierung ab: Der Endpunkt ist ohne Anmeldung
erreichbar, Einzelheiten zu Datenbank und Prozesszustand zeigt er aber nur einem
angemeldeten Aufrufer. Im Entwicklungsmodus (`dev`) gilt jede Anfrage als angemeldet, ein anonymes
`curl` sieht dort also die volle Aufschlüsselung; in einem OIDC-Deployment antwortet dasselbe `curl`
nur mit `{"status":"UP"}` bzw. `DOWN`, und die Aufschlüsselung braucht ein gültiges Zugangstoken.

**Private Bibliotheken im Log:** OPAA selbst schreibt Ordner, Dateien, Anhänge und Titel einer
privaten Bibliothek nicht ins Anwendungslog, auch nicht auf `DEBUG`; an ihrer Stelle steht
`<private library …>` mit der Kennung der Bibliothek, wo nötig mit der Kennung des Dokuments.
Fehlermeldungen ihrer Läufe, ihrer Aufnahme und ihres Originalabrufs erscheinen dort nur mit Typ und
Stack, ohne Meldungstext. Nicht abgedeckt sind die Protokollausgaben eingebundener Bibliotheken
(etwa Tika, PDFBox, HTTP-Client) auf `DEBUG` oder `TRACE`, Fehlermeldungen der Datenbank, die
Zeilenwerte nennen können, und der Eintrag „Unexpected error“ für einen unerwarteten Fehler einer
Anfrage. Wer das Log über `INFO` hinaus öffnet, sollte es deshalb wie Inhalte behandeln. Was die Besitzerin wissen muss, steht im
Laufprotokoll ihrer Bibliothek ([Benutzerverwaltung](benutzerverwaltung.md), „Verbundene Konten“).

## Ausgangsproxy

Muss das Backend über einen Proxy ins Netz, trägt die Systemverwaltung ihn in der Oberfläche ein:
**Administration → Anbindungen → Einstellungen**, Abschnitt **Ausgangsproxy**. Es gibt genau einen
Proxy für das ganze Haus, und er gilt nicht nur für Quellen, sondern auch für Sprachmodelle und die
Anmeldung bei Identitätsanbietern; ohne Eintrag gehen alle Verbindungen direkt hinaus. Die JVM-Eigenschaften `http.proxyHost` und die
Umgebungsvariablen `HTTP_PROXY`/`HTTPS_PROXY` wertet OPAA nicht aus.

- **Was über den Proxy geht:** jede HTTP(S)-Verbindung des Backends — Quellen der Bibliotheken samt
  Weiterleitungen und Original-Abruf, OAuth- und Token-Endpunkte der Zugänge, Dienstkonto-Tokens,
  MCP-Server, Chat-, Einbettungs- und Rerank-Endpunkte samt Verbindungstest, Discovery-Dokument und
  Signaturschlüssel der Identitätsanbieter und der Verzeichnisabgleich. `http`-Ziele gehen als
  gewöhnliche Proxy-Anfrage hinaus, `https`-Ziele als Tunnel (`CONNECT`).
- **Was nie über den Proxy geht:** Ziele auf `localhost` und Loopback-Adressen, die Datenbank, der
  Mailserver, SMB-Freigaben und der Objektspeicher der Originalablage.
- **Format:** `http://host:port` oder `host:port`. Ein Proxy über `https`, SOCKS und eine Anmeldung
  am Proxy werden nicht unterstützt; ein Proxy, der eine Anmeldung verlangt, weist jede Verbindung
  ab. Der Proxy muss OPAA deshalb über die Adresse des Servers zulassen.
- **Ausnahmeliste:** ein Eintrag je Zeile. Ein Hostname gilt für sich und alle Subdomänen
  (`intranet.example.org` trifft auch `wiki.intranet.example.org`; ein vorangestellter `.` oder `*.`
  bedeutet dasselbe), eine IP-Adresse für sich, ein Netzbereich wie `10.0.0.0/8` für IP-Adressen
  darin. Ein Netzbereich trifft nie einen Hostnamen, auch wenn dieser in den Bereich auflöst.
  Abgewiesen werden `*`, Ports und Leerzeichen. Interne Ziele wie ein Modellserver im eigenen Netz
  gehören auf diese Liste.
- **Ausnahmeliste und Zielprüfung sind getrennt.** Die Ausnahmeliste entscheidet nur über den Weg.
  Ob ein internes Ziel überhaupt erreicht werden darf, entscheidet weiter die Zielprüfung mit
  `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` (siehe oben). Ein internes Ziel braucht in der Regel
  beides.
- **Der Proxy bestimmt die Zieladresse.** Geprüft wird das Ziel, nie der Proxy. Ein Hostname, den der
  Server selbst nicht auflösen kann, wird nicht abgelehnt, wenn die Verbindung über den Proxy geht,
  denn der Proxy löst ihn auf. IP-Adressen und auflösbare Namen prüft OPAA unverändert. Welche
  Adresse der Proxy für einen Namen tatsächlich wählt, sieht OPAA nicht: Wer interne Ziele hinter dem
  Proxy ausschließen muss, sperrt sie am Proxy.
- **Änderungen** gelten für jede danach aufgebaute Verbindung, ohne Neustart. Eine bereits offene,
  wiederverwendete Verbindung (Keep-Alive) läuft auf ihrem bisherigen Weg weiter, bis sie schließt.
  Jede Änderung steht als `OUTBOUND_PROXY_CHANGED` mit altem und neuem Stand im Revisionsprotokoll.
- **Fehlerbild:** Lehnt der Proxy einen Tunnel ab oder verlangt er eine Anmeldung (Status 407),
  melden Verbindungstests und Läufe aller Quellarten, die Anmeldung an Zugängen, die
  Prüfung der Identitätsanbieter, der Verzeichnisabgleich und die Modell-Endpunkte, dass der Ausgangsproxy die
  Verbindung abgelehnt oder das Ziel nicht erreicht hat, mit dem Status des Proxys. Ausnahme ist
  S3: Dort erkennt OPAA nur die Anmeldung (Status 407). Einen abgelehnten Tunnel meldet der
  S3-Konnektor als Antwort des Objektspeichers, etwa als fehlende Berechtigung bei Status 403.
- **Notanker:** Ein falscher Proxy kann die Anmeldung über Identitätsanbieter lahmlegen, weil auch
  deren Signaturschlüssel über ihn geholt werden. Das lokale Konto der Systemverwaltung meldet sich
  ohne Anbieter an und kann den Eintrag korrigieren.

## Netzwerkzugang

Standardmäßig bindet das Backend an `localhost`. Um OPAA von anderen Geräten im Netzwerk zugänglich zu machen, setzen Sie:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

> **Hinweis:** In Docker Compose **muss** `OPAA_SERVER_ADDRESS` auf `0.0.0.0` gesetzt werden, damit das Backend vom Nginx-Reverse-Proxy des Frontend-Containers erreichbar ist.

> **TLS-terminierender Reverse-Proxy davor?** Das Backend wertet `X-Forwarded-*` aus (`server.forward-headers-strategy: framework`), damit Browser-Anfragen desselben Origins hinter dem Proxy nicht fälschlich als cross-origin behandelt werden. Der äußere Proxy **muss** `X-Forwarded-Proto` dabei autoritativ setzen (`proxy_set_header X-Forwarded-Proto $scheme;`) und darf den Wert nicht vom Client durchlassen — ein gespooftes `https` würde sonst die CORS-Prüfung umgehen. Der nginx im Frontend-Container reicht ein eingehendes `X-Forwarded-Proto` unverändert weiter (Fallback: eigenes Schema). Für **`X-Forwarded-For`** gilt eine andere Regel: Für Rate-Limits und Netzbeschränkung liest das Backend den Header — unabhängig von der Spring-eigenen Verarbeitung, die ihn sonst ungeprüft übernähme — **nur**, wenn die Verbindung aus einem Netz in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` kommt, und nimmt von rechts den ersten Eintrag, der kein vertrauter Proxy ist — der äußere Proxy darf die Kette also anhängen (`$proxy_add_x_forwarded_for`, wie es der nginx im Frontend-Container tut) oder überschreiben (`$remote_addr`), muss aber selbst in der Liste stehen (ein Proxy auf demselben Rechner erscheint dem Container-nginx unter der Gateway-Adresse des Compose-Netzes und muss mit ihr ergänzt werden — mit dem unter [„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments) genannten Risiko; ein Proxy auf einem anderen Rechner kommt mit seiner Adresse hinzu). Der äußere Proxy muss `X-Forwarded-For` dabei **autoritativ** setzen oder anhängen **und** ein vom Client eingehendes `Forwarded` (RFC 7239) verwerfen — der nginx im Frontend-Container tut Letzteres bereits (`proxy_set_header Forwarded "";`), weil das Backend diesen Header sonst dem `X-Forwarded-*`-Satz vorzieht. Ein Client, der den Header selbst setzt, erreicht damit nichts: Das Rate-Limit des Backends und die Netzbeschränkung lokaler Systemverwalter beurteilen ihn nach der so aufgelösten Adresse, und die Webhook-Eingänge für Confluence und S3 sind ohne Sitzung erreichbar, wären also sonst mit jedem Wert einen frischen Zähler wert. Ohne die Variable bleibt der Header wirkungslos und alle Clients hinter dem Proxy teilen sich einen Zähler (siehe [„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments)).

> **Die Rücksprungadresse `/connections/callback` ist eine Seite der Anwendung, kein Backend-Pfad.** Nach der Zustimmung bei einem OAuth-Anbieter (Zugänge mit Anmeldeart OAuth) leitet der Anbieter den Browser dorthin; die Seite reicht Code und `state` mit der Sitzung des Tabs an das Backend weiter. Ein eigener Reverse-Proxy reicht den Pfad deshalb wie `/` an den Frontend-Container, nicht an das Backend; die Auslieferung beantwortet ihn mit der Anwendung. Er darf die Abfrageparameter nicht protokollieren oder verändern: Sie tragen den einmal gültigen Code. Der mitgelieferte Frontend-Container schreibt sie für `/connections/callback` und `/auth/callback` nicht ins Zugriffsprotokoll, weder in die Anfragezeile noch in den Referer der Folgeanfragen dieser Seiten; alle übrigen Anfragen protokolliert er wie das Format `main` des Basis-Images. Die Adresse muss genau `{OPAA_PUBLIC_BASE_URL}/connections/callback` lauten, so wie sie in der App-Registrierung beim Anbieter steht; ein Unterschied in Schema, Host oder Port lässt den Anbieter die Anmeldung abweisen.

> **Zeitgrenze des äußeren Proxys.** Eine Chat-Antwort ist eine einzige Antwort auf eine
> `POST`-Anfrage, die erst kommt, wenn Suche, Reranking und Modellaufruf fertig sind. Der nginx im
> Frontend-Container wartet darauf `OPAA_BACKEND_READ_TIMEOUT` lang. Ein eigener Reverse-Proxy vor
> dem Frontend-Container braucht für `/api/` und `/mcp` mindestens dieselbe Wartezeit (bei nginx
> `proxy_read_timeout`, dessen Vorgabe nur eine Minute beträgt) — sonst bricht er lange Antworten
> mit `504` ab, während das Backend noch rechnet.

> **Der MCP-Pfad `/mcp` gehört mit durchgereicht.** Der nginx im Frontend-Container reicht neben `/api/` auch den MCP-Endpunkt des Fremdzugangs an das Backend weiter (`frontend/nginx.conf`, eigener `location`-Block). Ein eigener Reverse-Proxy vor dem Frontend-Container muss `/mcp` deshalb genauso an diesen Container weiterreichen wie `/` — sonst beantwortet die Auslieferung der Anwendung den Aufruf mit ihrer eigenen Startseite, und ein MCP-Client meldet eine unverständliche Antwort statt eines Zugangsfehlers. Antworten dieses Pfads darf der äußere Proxy nicht puffern: Der Kanal kann als Ereignisstrom antworten.

### Sicherheits-Header und `Strict-Transport-Security`

Der nginx im Frontend-Container (`frontend/nginx.conf`) setzt `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` und `server_tokens off` auf jede Antwort (auch Fehlerantworten, über `add_header ... always;`). Die Content-Security-Policy setzt technisch durch, dass das Frontend alle Schriften, Icons und statischen Ressourcen selbst ausliefert und zur Laufzeit kein CDN lädt: Skripte, Stile, Schriften und Verbindungen sind auf die eigene Herkunft begrenzt, keine externe Quelle ist erlaubt (bis auf die unten beschriebene, gezielte Ausnahme für den OIDC-Anbieter). `style-src` erlaubt zusätzlich `'unsafe-inline'`, weil MUIs Emotion-Engine Stile zur Laufzeit über eingebettete `<style>`-Tags einfügt — dafür gibt es ohne Nonce-Unterstützung in Emotion keinen strikteren Weg. `object-src 'none'` ist gesetzt, weil die Anwendung keine `<object>`/`<embed>`-Inhalte einbettet.

Auf `/api/`-Antworten setzt zusätzlich Spring Security eigene `X-Content-Type-Options`/`X-Frame-Options`-Header; `proxy_hide_header` in den beiden Weiterleitungsblöcken (`/api/` und der MCP-Pfad) entfernt diese, damit der Client nur die eine, am nginx gesetzte Kopie sieht statt beide Werte doppelt.

**`Strict-Transport-Security` wird hier bewusst nicht gesetzt.** Der Frontend-Container terminiert im Compose-Betrieb kein TLS — er spricht selbst nur `http` (siehe Hinweis zu `X-Forwarded-Proto` oben). Diesen Header trotzdem hier zu setzen wäre wirkungslos für Installationen ohne vorgelagerten TLS-Weg und schädlich für solche mit einem: HSTS an zwei Stellen im selben Antwortpfad zu pflegen — hier und am vorgelagerten Proxy — schafft nur eine weitere Möglichkeit, dass beide auseinanderlaufen (z. B. unterschiedliches `max-age` oder `includeSubDomains`), ohne einen Sicherheitsgewinn gegenüber einer einzigen, korrekt gepflegten Stelle. Wer OPAA hinter einem TLS-terminierenden Proxy betreibt (siehe Hinweis oben), setzt `Strict-Transport-Security` an genau diesem äußeren Proxy — dort, wo TLS tatsächlich endet.

Die genannten Header sind gegen den Produktions-Build (`pnpm run build`) und das gebaute Docker-Image verprobt: `dist/index.html` referenziert ausschließlich selbst gehostete, gehashte `<script>`- und `<link>`-Dateien (keine Inline-Skripte), und ein Abruf des laufenden Containers zeigt alle Header sowohl auf `/` als auch auf `/api/`-Antworten (Vererbung über den `server`-Block, siehe Kommentare in `frontend/nginx.conf`).

Für die lokale Entwicklung auch das Frontend mit `pnpm run dev --host` starten und den Zugriffsursprung zu CORS hinzufügen:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

## Härtung für erreichbare Deployments

### Sicherheitshinweis: `POST /api/v1/libraries/{libraryId}/indexing` ist von außen erreichbar

Bei jeder über einen Reverse-Proxy erreichbaren Installation ist dieser Endpunkt aus dem Internet
erreichbar. Dass alle Container-Ports nur auf `127.0.0.1` binden (siehe „Härtung für erreichbare
Deployments" unten), ändert daran nichts — der nach außen gerichtete Proxy reicht die API-Pfade
durch, und der Indizierungspfad ist davon nicht ausgenommen. Die Bindung auf `127.0.0.1` verhindert
lediglich, dass jemand die Container unter Umgehung des Proxys direkt anspricht.

Er ist durch Keycloak authentifiziert und verlangt mindestens die Rolle `EDITOR` auf der Zielbibliothek (`DocumentIndexingService#requireEditableLibrary`); eine zusätzliche `SYSTEM_ADMIN`-Schranke gibt es bewusst nicht — ein Anstoß-Knopf, den nur die Systemverwaltung drücken darf, wäre für jeden anderen Bibliothekseigentümer tot. Zusätzlich greift das Rate Limiting mit einem eigenen, engen Kontingent für diesen Pfad (`OPAA_RATE_LIMIT_INDEXING_*`, standardmäßig eine Anfrage pro IP und Minute).

Wer eine Bibliothek vom Typ `HTTP_DIRECTORY` oder `RSS_FEED` anlegt, bestimmt bei der Anlage deren `sourceUrl` — eine beliebige Adresse, die der Server bei jedem Lauf abruft und crawlt. Das schließt Ziele ein, die nur aus dem Netz des VPS erreichbar sind und nicht aus dem Internet. Fachlich ist das serverseitige Anfragefälschung (Server-Side Request Forgery, SSRF) — hier allerdings als **Eigenschaft der Funktion**, nicht als Fehler: Der Endpunkt existiert genau dafür, entfernte Dokumentenquellen zu erschließen, und er verlangt Anmeldung plus mindestens Bearbeitungsrecht auf der Bibliothek.

**Ist-Zustand der Einschränkungen** (geprüft an `KnowledgeLibraryService`, `UrlIndexingExecutor`, `RssFeedIndexingExecutor`, `AutoindexCrawlerService` und `TargetAddressValidator`):

- Das Schema wird von OPAA selbst geprüft (nicht nur als Nebenwirkung des `java.net.http.HttpClient`, der ohnehin nur `http`/`https` akzeptiert): Ein anderes Schema wird mit einer deutschen Meldung abgelehnt, bevor überhaupt eine Verbindung versucht wird.
- Eine **Blockliste privater und lokaler Adressbereiche ist aktiv** (`TargetAddressValidator`, standardmäßig eingeschaltet): Loopback (`127.0.0.0/8`, `::1`), Link-Local einschließlich der Cloud-Metadaten-Adresse `169.254.169.254` (`169.254.0.0/16`, `fe80::/10`), die privaten IPv4-Bereiche (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), reserviert einschließlich Broadcast (`240.0.0.0/4`, `255.255.255.255`), IETF Protocol Assignments (`192.0.0.0/24`), Benchmarking (`198.18.0.0/15`), IPv6 Unique-Local (`fc00::/7`), NAT64 (`64:ff9b::/96`) sowie IPv4-in-IPv6-eingebettete Adressen — sowohl das aktuelle `::ffff:a.b.c.d` (IPv4-mapped) als auch das veraltete `::a.b.c.d` (IPv4-compatible) — werden abgelehnt, geprüft anhand des eingebetteten IPv4-Werts. Geprüft wird durchgehend die **aufgelöste** IP-Adresse, nicht der Hostname. Die Prüfung greift vor dem ersten Abruf **und** nach jeder Weiterleitung, damit ein Redirect eine erlaubte Startadresse nicht auf eine gesperrte Adresse umlenken kann. Abschaltbar (`OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false`) oder um konkrete Hostnamen erweiterbar (`OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`) für Betriebe mit legitimen internen Quellen — siehe die Tabelle unten.
- **DNS-Rebinding bleibt eine grundsätzliche, dokumentierte Grenze:** Die Auflösung, die geprüft wird, und die Auflösung, mit der `HttpClient` tatsächlich verbindet, sind zwei getrennte Vorgänge — ein Resolver, der zwischen beiden eine andere Antwort gibt, wird von dieser Prüfung nicht erkannt. Der JDK-`HttpClient` bietet keinen unterstützten Weg, eine Verbindung auf eine bereits geprüfte Adresse festzulegen. Das ist Härtung gegen naheliegende Fehlgriffe und Missbrauch, keine vollständige Garantie. Dieselbe Grenze gilt für die übrigen Datenquellen (S3, Confluence) und für die Adressprüfung der Identitätsanbieter (Issuer, JWK-Set, `jwks_uri` aus dem Discovery-Dokument) und der Admin-API des Verzeichnisabgleichs. Wer das Restrisiko ausschließen muss, beschränkt die ausgehenden Verbindungen des Backends zusätzlich auf Netzebene (Egress-Regeln oder ein Proxy mit Zielliste).
- Weiterleitungen werden gefolgt, aber nicht über `HttpClient.Redirect.NORMAL`: Der `HttpClient` steht auf `Redirect.NEVER`, jede Weiterleitung wird manuell verfolgt und dabei gegen den ursprünglichen Origin geprüft (Schema, Host, normalisierter Port) — eine Weiterleitung auf einen fremden Host wird abgelehnt, statt ihr zu folgen, ebenso ein Downgrade von `https` auf `http`. Ein Upgrade von `http` auf `https` auf demselben Host (Standard-Ports oder identischer expliziter Port) gilt dagegen als dieselbe Origin und wird gefolgt, Zugangsdaten eingeschlossen.
- Mit `sourceInsecureSsl: true` lässt sich die Zertifikatsprüfung abschalten. Bei `HTTP_DIRECTORY` gilt das für den gesamten Crawl (Verzeichnislisting und jede darüber gefundene Datei). Bei `RSS_FEED` dagegen nur für den Origin der konfigurierten `sourceUrl` selbst: Eine Detailseite oder Anlage, auf die der Feed-Inhalt verweist und die auf einem fremden Origin liegt, wird weiterhin normal geprüft, unabhängig von `sourceInsecureSsl` — der Feed-Betreiber kontrolliert diesen Inhalt, nicht der Bibliothekseigentümer.
- **Einen eigenen Proxy tragen weder Bibliothek noch Zugang.** Alle Verbindungen gehen über den hausweiten [Ausgangsproxy](#ausgangsproxy), den nur die Systemverwaltung setzt; geprüft wird weiter das Ziel.
- Fehlermeldungen des Crawls landen im Jobstatus und sind über `GET /api/v1/libraries/{libraryId}/indexing/status` lesbar; sie unterscheiden erreichbare von nicht erreichbaren Zielen, eine abgelehnte Weiterleitung (Ziel als `Schema://Host` ohne Pfad/Query, nie die vollständige, potenziell sensible Ziel-URL) und ein per Zielprüfung gesperrtes Ziel.

**Risikoeinordnung.** Die Anlage jedes Quellentyps steht bewusst und dauerhaft jedem Berechtigten offen (nicht nur `SYSTEM_ADMIN`) — kein Rollenkonstrukt tritt an die Stelle dieser Öffnung. Für `FILESYSTEM` ist die daraus entstehende Angriffsfläche über die Pfad-Allowlist (`opaa.indexing.filesystem.allowlist`) geschlossen: Sie sichert, welche Serverpfade überhaupt konfigurierbar sind, unabhängig davon, wer die Bibliothek anlegt. Für `HTTP_DIRECTORY`/`RSS_FEED` schließt die Zielprüfung (`opaa.indexing.target-validation`) die analoge Lücke: Sie sichert, welche Adressbereiche überhaupt erreichbar sind, ebenfalls unabhängig davon, wer die Bibliothek anlegt.

**Konsequenz für den Betrieb:** Für `FILESYSTEM` schützt die Allowlist, für `HTTP_DIRECTORY`/`RSS_FEED`/`CONFLUENCE`/`S3` die Zielprüfung — beide unabhängig davon, wem das Anlage-Recht zusteht. Ein Betrieb mit legitimen internen Dokumentenquellen (z. B. einem Intranet-Portal) trägt deren Hostnamen gezielt in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` ein, statt die Prüfung insgesamt abzuschalten.

Weist ein `sourcePath` außerhalb der Allowlist zurück, nennt die 400-Antwort bewusst keine Serverpfade — das würde die konfigurierten Basisverzeichnisse gegenüber einem nicht berechtigten Aufrufer preisgeben. Damit der abgewiesene Aufrufer trotzdem handlungsfähig bleibt, verweist die Meldung stattdessen auf die Systemverwaltung als Anlaufstelle für die freigegebenen Basisverzeichnisse.

**Zugangsdaten, die zusätzlich zu ersetzen sind (empfohlen, unabhängig von den Fundstellen der Tabelle oben):**

- `OPAA_DB_USERNAME`/`OPAA_DB_PASSWORD` — die Vorgabewerte `opaa`/`opaa` sind für die Entwicklung gewählt,
  nicht für den Betrieb. Eine Änderung nach der Ersteinrichtung verlangt `docker compose down -v` (siehe
  [„Datenbank"](#datenbank) unten), weil PostgreSQL den initialen Benutzer nur beim ersten Start anlegt —
  das Zugangsdatenpaar also **vor** dem ersten Start setzen, nicht danach ändern wollen.
- `OPAA_CREDENTIALS_ENCRYPTION_KEY` — sobald eine Bibliothek vom Typ `HTTP_DIRECTORY`/`RSS_FEED`
  Zugangsdaten zu einer externen Quelle speichert (siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung)
  unten), muss ein eigener Schlüssel gesetzt sein — ohne ihn schlägt nur dieser eine Schreibvorgang fehl,
  der Start selbst nicht. Der im `dev`-Profil hinterlegte Schlüssel ist ausdrücklich nicht
  produktionstauglich und darf nicht übernommen werden.
- `OPAA_SETTINGS_ENCRYPTION_KEY` — sobald ein Chat-Modell mit Zugangsschlüssel angelegt oder
  geändert wird (siehe [„Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle"](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle)
  unten), muss ein eigener Schlüssel gesetzt sein — ohne ihn schlägt nur dieser eine Vorgang fehl,
  der Start selbst nicht. Der im `dev`-Profil hinterlegte Schlüssel ist ausdrücklich nicht
  produktionstauglich und darf nicht übernommen werden.

- `OPAA_AUTH_JWT_SECRET` — das Wurzelgeheimnis der lokalen Benutzerverwaltung, aus dem OPAA die
  Schlüssel für lokal ausgestellte Tokens und die Prüfwerte der Refresh- und Aktionslinks ableitet;
  im Profil `oidc` **Pflicht** (mindestens 32 Zeichen, kein Platzhalter — sonst bricht der Start mit
  einer Meldung ab), erzeugt mit `openssl rand -base64 48`. Eine Rotation beendet alle lokalen
  Sitzungen und entwertet alle offenen Einladungs-, Rücksetz- und Übergabelinks — bewusst der eine
  Handgriff nach einer Datenbank-Rücksicherung. Tokens von OIDC-Anbietern verifiziert OPAA weiterhin
  ausschließlich gegen deren Signaturschlüssel (`OPAA_OIDC_JWK_SET_URI`); dieses Secret betrifft nur
  lokale Konten.

**Vertraute Proxys und die Grenzen der lokalen Anmeldung (zwingend hinter einem Reverse-Proxy):**

- `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` — **Pflicht, sobald ein Reverse-Proxy vor dem Backend
  steht**, und der nginx des Frontend-Containers ist bereits einer. Das Backend schlüsselt seine
  Rate-Limits je Client-Adresse und prüft die Netzbeschränkung lokaler Systemverwalter
  (`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`) an derselben Adresse; `X-Forwarded-For` wird dabei **nur**
  ausgewertet, wenn die Verbindung selbst aus einem der hier genannten Netze kommt. Ohne Wert (die
  Vorgabe) wird der Header ignoriert: Hinter dem Proxy sehen dann alle Clients wie eine Adresse aus
  und teilen sich einen Zähler — die Anmeldegrenze gilt dann für das ganze Haus, und die
  Netzbeschränkung sieht nur die Adresse des Proxys. Der `oidc`-Betriebsmodus schreibt bei leerem
  Wert genau diese Folge beim Start als Warnung ins Log. Für den Compose-Stack ist der Wert die
  **feste Adresse des Frontend-Containers** (`docker-compose.yml` legt Subnetz und Adresse fest,
  `.env.docker.example` trägt sie als `/32` ein) — bewusst nicht das ganze Compose-Netz: Dessen
  Gateway-Adresse spricht jeder Prozess des Docker-Hosts und jeder Container, und wer sie vertraut,
  macht jeden davon zum Proxy, der `X-Forwarded-For` beliebig setzen kann (der Backend-Port ist
  deshalb außerdem nur an `127.0.0.1` gebunden). Steht ein weiterer Proxy auf **demselben** Host
  vor dem Frontend-Container, erscheint er dem Container-nginx unter genau dieser Gateway-Adresse
  und muss zusätzlich eingetragen werden — ein bewusster Zusatz mit genau diesem Risiko, keine
  Bequemlichkeit; ein Proxy auf einem anderen Rechner kommt mit seiner eigenen Adresse hinzu. Der
  Header wird von rechts gelesen: Der Client ist der erste Eintrag, der kein vertrauter Proxy ist —
  ein vom Client selbst mitgeschickter Anfang der Kette zählt nie, und ein weiterer vertrauter Proxy
  davor wird übersprungen; mehrere `X-Forwarded-For`-Zeilen gelten als eine Kette. Bereiche weiter
  als `/8` (`0.0.0.0/0`, `::/0`) lehnt der Start ab. Ob die Liste stimmt, zeigt der
  Diagnose-Endpunkt unter [„Diagnose ohne Shell"](#diagnose-ohne-shell).

**Woran erkennbar ist, was zwingend und was nur empfohlen ist:** Die Tabelle und die Liste oben markieren
jeden Punkt einzeln als „Zwingend" oder „Empfohlen". Als Faustregel: Ein bekanntes, öffentlich im
Repository stehendes Zugangsdatenpaar, ein Klartext-Transportweg zu Anmeldedaten oder ein netzweit
erreichbarer administrativer Zugang ist immer zwingend zu schließen; eine Einschränkung, die nur die
Angriffsfläche auf einem sonst schon vertrauenswürdigen Host verkleinert (etwa Teile von Punkt 5), ist
eine Empfehlung.

## Zugangsdaten-Verschlüsselung

Eine Bibliothek vom Quellentyp `HTTP_DIRECTORY`, `RSS_FEED`, `CONFLUENCE` oder `S3` kann Zugangsdaten
(`sourceCredentials`, bei HTTP-Quellen im Basic-Auth-Format `user:password`) tragen. Diese liegen **verschlüsselt** in
der Datenbank (AES-256-GCM, zufälliger Initialisierungsvektor je Wert) und erscheinen in keiner
API-Antwort und keinem Log — die API behandelt das Feld als
Nur-Schreiben-Feld.

**Schlüssel erzeugen und setzen:**

```bash
openssl rand -base64 32
```

Das Ergebnis (ein Base64-kodierter 256-Bit-Schlüssel) als `OPAA_CREDENTIALS_ENCRYPTION_KEY` setzen
— **nicht** ins Repository committen, wie jede andere Zugangsinformation außerhalb von
Umgebungsvariablen behandeln (Passwort-Tresor, Secret-Manager der Zielumgebung o. Ä.).

**Kein Zwang, keinen zu haben:** Ohne gesetzten Schlüssel startet das Backend normal — die meisten
Bibliotheken (`UPLOAD`, `FILESYSTEM`, oder ein `HTTP_DIRECTORY`/`RSS_FEED` ohne Zugangsdaten)
brauchen keinen. Erst der Versuch, eine Bibliothek **mit** Zugangsdaten anzulegen oder zu ändern,
schlägt ohne gültigen Schlüssel mit `503` fehl (`io.opaa.security.CredentialsEncryptor`) — eine klare
Fehlermeldung statt eines unspezifischen `500`. Für lokale Entwicklung und Tests (nur Profil `dev`,
das jede Testsuite und `bootRun` aktivieren — nicht `local`) ist ein fest hinterlegter, **ausdrücklich
nicht produktionstauglicher** Schlüssel voreingestellt (`backend/src/main/resources/application.yml`),
damit beide ohne Betreiber-Eingriff laufen.

**Lesen scheitert weich, Schreiben hart:** Ein Wert, der mit dem aktuellen Schlüssel nicht mehr
entschlüsselt werden kann (Schlüssel verloren, rotiert, oder ein beschädigter Datenbankwert), lässt
das **Lesen** dieser einen Bibliothek nicht scheitern — `SourceCredentialsConverter` protokolliert
eine Warnung (ohne den Wert, ohne die Bibliotheks-ID) und behandelt `sourceCredentials` für diese
Bibliothek als nicht gesetzt; `GET /api/v1/libraries` liefert die übrige Liste normal, auch wenn eine
einzelne Bibliothek betroffen ist. Ein **Schreibvorgang** (Anlegen oder Ändern von Zugangsdaten)
scheitert dagegen weiterhin hart mit `503`, statt Zugangsdaten stillschweigend zu verlieren.

**Eine Änderung an der Bibliothek lässt den Geheimtext unangetastet:** Solange der Schlüssel fehlt,
liest die Anwendung die Zugangsdaten und das Push-Geheimnis (`source_credentials`,
`source_webhook_secret`) als nicht gesetzt — ein Schreibvorgang auf die Bibliothek speichert diesen
gelesenen Leerwert aber nicht zurück. Name, Beschreibung, Metadaten-Einstellungen, Zeitplan und jede
andere Änderung schreiben nur die Spalten, die sie selbst geändert haben; der gespeicherte Geheimtext
bleibt stehen und ist mit dem wiedergefundenen Schlüssel unverändert lesbar. Die Oberfläche zeigt die
Zugangsdaten in diesem Zustand allerdings als „nicht gesetzt“ an, obwohl sie gespeichert sind — das
Ja/Nein wird aus dem gelesenen Wert abgeleitet. Zusätzlich hinterlässt jeder solche Lesevorgang eine
Warnung im Protokoll (`io.opaa.knowledge.SourceCredentialsConverter`, ohne den Wert und ohne die
Bibliotheks-ID). Für eine Bibliothek an einem Zugang liegen die Zugangsdaten im Token-Speicher; die
Anzeige leitet sich dort ebenso aus dem gelesenen Wert ab, die Warnung kommt von
`io.opaa.connection.token.ConnectionSecrets` und nennt statt der Bibliothek die ID des Zugangs.

**Zwei Vorgänge löschen den Geheimtext auch ohne Schlüssel — absichtlich:** ein Wechsel des Hosts in
`sourceUrl`, ohne neue Zugangsdaten mitzusenden (die gespeicherten Zugangsdaten gelten nur für den
bisherigen Host und werden bei einem Wechsel verworfen — sonst ließen sie sich an einen fremden Host
umleiten), und der ausdrückliche Widerruf des Webhook-Geheimnisses bzw. Ereignis-Tokens. Beide wirken
auf die gespeicherte Spalte, nicht auf den gelesenen Wert; ein Widerruf ist damit auch in diesem
Zustand endgültig.

**Bei Schlüsselverlust:** Bereits verschlüsselte `sourceCredentials`-Werte sind ohne den
ursprünglichen Schlüssel nicht wiederherstellbar — es gibt keinen Wiederherstellungsweg außerhalb des
Schlüssels selbst. Betroffen sind ausschließlich die Zugangsdaten selbst, nicht die übrige
Bibliothekskonfiguration oder der bereits indizierte Bestand. Abhilfe: Zugangsdaten über die
bestehende Bibliotheks-API (`PATCH /api/v1/libraries/{id}`) neu setzen — derselbe Weg, über den
Zugangsdaten ohnehin rotiert werden. Dank des weichen Lesefehlers oben funktioniert dieser
Reparaturweg tatsächlich: das vorausgehende Laden der Bibliothek scheitert nicht mehr an genau dem
Wert, der repariert werden soll. Bis zur Reparatur läuft ein laufender oder künftiger
Indizierungslauf für diese Bibliothek ohne Anmeldung und kann entsprechend scheitern, wenn die
Quelle Zugangsdaten verlangt.

**Altbestand im Klartext:** Werte, die vor Einführung dieser Verschlüsselung im Klartext geschrieben
wurden, erkennt `CredentialsEncryptor` beim Lesen an einem fehlenden `enc:`-Präfix und behandelt sie
weiterhin als Klartext — eine Liquibase-Migration kann sie nicht verschlüsseln, da der Schlüssel nur
der Anwendung bekannt ist. Der nächste Schreibvorgang auf dieselbe Bibliothek (z. B. eine
Zugangsdaten-Rotation über die bestehende Update-API) verschlüsselt den Wert. Ein Wert mit `enc:`-
Präfix, aber unbekannter Version (nicht `enc:v1:`), wird dagegen nie als Klartext behandelt — Lesen
scheitert weich (siehe oben), Schreiben hart.

## Was ein Update mit dem Index macht

Kurz: Ein Update über `docker compose pull` + `docker compose up -d` **gefährdet den Index nicht** und macht **keine Neuindizierung erforderlich**. Im Einzelnen:

- **Der Korpus** liegt in einem Bind-Mount auf dem Host und ist von Container-Neustarts unberührt.
- **Der Index** liegt in PostgreSQL, dessen Daten im benannten Volume `opaa-postgres-data` liegen. Das Volume überlebt das Neuerstellen der Container; nur `docker compose down -v` löscht es.
- **Liquibase** wendet beim Backend-Start ausschließlich noch nicht angewendete Changesets vorwärts an; keines davon löscht Dokument- oder Vektordaten. Die Ausnahme ist eine zu einer neuen Baseline zusammengefasste Historie: Dann ist das Update ein Neuaufsetzen, siehe [„Migrationen aus älteren Ständen"](#migrationen-aus-älteren-ständen).
- **Die Vektortabelle** wird nicht von Liquibase, sondern von Spring AI selbst angelegt (`spring.ai.vectorstore.pgvector.initialize-schema: true`). Sie wird nur erzeugt, wenn sie fehlt, und bei einem Update nicht verändert.

> **Der Vektorspeicher ist nicht wählbar.** OPAA speichert Vektoren in PostgreSQL mit pgvector; das ist der einzige unterstützte Vektorspeicher. Der Zugriff läuft zwar über eine portable Schnittstelle von Spring AI, ein Wechsel wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert. Metadatenbestand und Vektorindex liegen damit in derselben Datenbank: ein Sicherungslauf, eine Wiederherstellung, ein Nachweispfad. Bekannte Grenze: pgvector stößt bei sehr großen Beständen im Bereich von Millionen Vektoren an Grenzen. OPAA braucht **pgvector 0.8.0 oder neuer** (das Image `pgvector/pgvector:pg18` bringt eine passende Version mit); bei einer älteren Erweiterung bricht der Start mit einer Meldung ab. Wer die Datenbank selbst betreibt und pgvector aktualisiert, führt danach `ALTER EXTENSION vector UPDATE;` aus.

Eine Neuindizierung wird erst durch Änderungen nötig, die nichts mit dem Image-Update zu tun haben:

| Auslöser | Folge |
|---|---|
| Embedding-Modell oder -Anbieter gewechselt | Bestehende Vektoren stammen aus einem anderen Modell und sind nicht mehr vergleichbar — vollständige Neuindizierung nötig. **`OPAA_PGVECTOR_DIMENSIONS` muss mitgezogen werden**, sonst passt die Vektorbreite nicht zum neuen Modell |
| `OPAA_PGVECTOR_DIMENSIONS` geändert | Passt nicht mehr zur bestehenden Vektortabelle — die Datenbank muss zurückgesetzt und der Korpus neu indiziert werden. Enthält `vector_store` bereits Vektoren mit abweichender Dimension, bricht der Start selbst mit einer klaren Fehlermeldung ab (`io.opaa.config.PgVectorDimensionsGuard`), statt erst beim ersten Indizierungslauf kryptisch zu scheitern |
| `docker compose down -v` bzw. das Deployment-Skript mit zurücksetzendem Schalter ausgeführt | Datenbank inklusive Index ist weg — vollständige Neuindizierung nötig |
| PostgreSQL-Hauptversion gewechselt (Image-Tag von `pg18` auf eine höhere Version) | Das Datenverzeichnis im Volume ist nicht aufwärtskompatibel; ein solcher Wechsel ist ein eigener Migrationsvorgang, kein `docker compose pull` |
| Update von einer Version **vom 22.08.2026 bis zum 23.08.2026** (jeder Stand, der Vektoren über die OpenAI-kompatible Schicht indiziert hat, bevor die Metadaten-Kontamination der Einbettung am 23.08.2026 behoben wurde) | In dieser Zeitspanne indizierte Vektoren tragen fünf Zeilen Metadaten-Rauschen (`document_id`/`chunk_index`/`file_name`/`library_id`/`organization_id`) vor dem eigentlichen Text mit eingebettet (Kosinus-Ähnlichkeit zum sauberen Vektor: rund 0.42) — **betroffene Bibliotheken müssen neu indiziert werden**, sonst liegen kontaminierte und saubere Vektoren nebeneinander im selben Suchraum. Siehe die eigene Anleitung dazu direkt unten |
| Update auf einen Stand **ab dem 27.08.2026** (Contextual Chunking: ein aus dem Dateinamen abgeleiteter Titel wird jedem Chunk eines mehrchunkigen Dokuments als Kontext-Präfix vorangestellt, nur für die Einbettung) | Zuvor eingebettete Vektoren tragen kein Präfix und ranken inkonsistent gegen neu eingebettete Vektoren mit Präfix im selben Suchraum — **jede Bibliothek muss vollständig neu indiziert werden**. Siehe die eigene Anleitung dazu direkt unten |
| Update auf einen Stand **ab dem 01.09.2026** (Ingestion-Pipelines je Dokumenttyp: eigene Zuschnitte für `.pdf`/`.docx`/`.pptx`/`.xlsx`/`.csv`/`.ods`/`.html`/`.eml`/`.msg` statt des einheitlichen Tika-Wegs, ab dem 02.09.2026 auch `.odt`/`.odp`) | Bestand, der vor der jeweiligen Pipeline-Registrierung indiziert wurde, liegt mit dem alten, generischen Zuschnitt im selben Suchraum neben neu erzeugten Chunks der spezialisierten Pipeline — **je Pipeline gezielt nachziehen**, kein Bibliotheks-Reset nötig. Siehe die eigene Anleitung dazu direkt unten |

Wer ein Compose-Profil mit fest gekoppeltem Embedding-Modell und `OPAA_PGVECTOR_DIMENSIONS` betreibt
(so läuft die öffentliche Demo-Instanz mit `nomic-embed-text` und `768`), muss bei einem Wechsel des
Embedding-Modells beide Werte gemeinsam ändern und die Datenbank zurücksetzen. Ein Wechsel des
**Chat**-Modells berührt den Index dagegen nicht, sofern Chat und Einbettung getrennte Anbieter sind.

> **Falle bei einer Neuindizierung:** OPAA überspringt Dateien, deren SHA-256-Prüfsumme unverändert ist **und** deren Datensatz in der Tabelle `documents` den Status `INDEXED` trägt. Wird die Vektortabelle geleert, ohne auch `documents` zu bereinigen, meldet ein neuer Lauf lauter übersprungene Dateien und der Index bleibt leer. Beide Tabellen liegen in derselben Datenbank — wer den Index verwirft, muss `documents` mitverwerfen.

> **Neuindizierung nach der Behebung der Metadaten-Kontamination (23.08.2026):** Der Fix wirkt nur vorwärts — er ändert, was ab dem Update neu eingebettet wird, nicht die bereits gespeicherten Vektoren. Zwischen der Umstellung auf die OpenAI-kompatible Einbettung (22.08.2026) und diesem Fix indizierte Chunks bleiben kontaminiert, bis sie explizit neu indiziert werden, und liegen bis dahin unbemerkt neben sauberen Vektoren im selben Suchraum — eine gemischte Suchqualität, kein Fehlschlag, der auffällt. Betroffen ist jede Bibliothek, die in diesem Zeitfenster (mindestens einmal) indiziert wurde, unabhängig vom Quellentyp. Eine reine `docker compose pull`/`up -d`-Aktualisierung erkennt das nicht automatisch — wer aus dieser Zeitspanne aktualisiert, muss selbst neu indizieren. Zwei bekannte Fallen dabei, zusätzlich zur SHA-256-/`INDEXED`-Falle oben:
>
> - **`FILESYSTEM`/`HTTP_DIRECTORY`:** Die SHA-256-Falle oben gilt unverändert — unveränderte Dateien mit Status `INDEXED` werden übersprungen, auch wenn ihr gespeicherter Vektor kontaminiert ist. Ohne einen Rücksetzschritt merkt ein neuer Lauf gar nichts an.
> - **`RSS_FEED`:** Zusätzlich zur SHA-256-Falle greift hier der `rss_feed_state`-ETag/`Last-Modified` (siehe [RSS-Feed](konnektoren/rss-feed.md), Abschnitt „Änderungserkennung"): Meldet der Feed „unverändert" (HTTP 304), endet der Lauf nach der ersten Anfrage, ohne dass auch nur ein Eintrag erneut betrachtet wird — unabhängig vom Zustand der bereits gespeicherten Vektoren.
>
> Für einen echten Neuaufbau einer betroffenen Bibliothek müssen **`documents`, die zugehörigen `vector_store`-Zeilen und (nur bei `RSS_FEED`) `rss_feed_state`** gemeinsam zurückgesetzt werden, gezielt für die betroffene `library_id` — nicht die ganze Datenbank:
>
> ```sql
> DELETE FROM vector_store WHERE metadata->>'library_id' = '<library-id>';
> DELETE FROM documents WHERE library_id = '<library-id>';
> DELETE FROM rss_feed_state WHERE library_id = '<library-id>';  -- nur bei RSS_FEED-Bibliotheken
> ```
>
> Anschließend die Bibliothek regulär neu indizieren (`POST /api/v1/libraries/{libraryId}/indexing` bzw. der entsprechende Button in der Oberfläche). Es gibt keinen dedizierten „Nur-neu-einbetten"-Schalter, der die drei Tabellen automatisch zurücksetzt — dieser manuelle Weg ist der einzige. Betroffen ist jede Bibliothek, die in diesem Zeitfenster mindestens einmal indiziert wurde.

> **Neuindizierung nach Einführung des Contextual Chunking (27.08.2026):** Anders als beim Fix oben
> ist das kein Fehler, sondern
> eine Erweiterung dessen, was in die Einbettung eingeht — ein aus dem Dateinamen abgeleiteter,
> bereinigter Titel als Kontext-Präfix, aber nur für Chunks eines Dokuments, das beim Chunking in 2
> oder mehr Chunks zerfiel (`DocumentIngestService#storeChunks`; ein einchunkiges Dokument bleibt
> bit-identisch zum vorherigen Stand). Der gespeicherte Chunk-Text (`content` in `vector_store`) und
> damit jedes Zitat bleiben in beiden Fällen unverändert, nur der Vektor selbst ändert sich, und auch
> nur für mehrchunkige Dokumente. **Das ist keine reine Verbesserung, sondern verschiebt auch das
> relative Ranking innerhalb einer Bibliothek:** Ein einchunkiges Dokument bekommt selbst nie ein
> Kontext-Präfix und kann deshalb nach dem Reindex relativ schlechter ranken als ein thematisch
> verwandtes, jetzt präfixiertes mehrchunkiges Dokument in derselben Bibliothek. Vor und nach diesem
> Stand eingebettete Chunks eines mehrchunkigen Dokuments liegen deshalb im
> selben pgvector-Suchraum nebeneinander, ranken aber inkonsistent gegeneinander (ein Alt-Chunk
> konkurriert ohne das Präfix-Signal gegen Neu-Chunks, die es haben) — ein **vollständiger Reindex
> jeder Bibliothek** ist nach diesem Update erforderlich, unabhängig vom Quellentyp und unabhängig
> davon, ob eine Datei sich inhaltlich geändert hat (die Split-Entscheidung selbst lässt sich vorab
> nicht ohne einen Parse-/Chunking-Lauf feststellen). Dieselbe SHA-256-/`INDEXED`-Falle wie oben
> gilt: Ein unveränderter Datensatz wird ohne einen Rücksetzschritt übersprungen, obwohl sein Vektor
> (bei einem mehrchunkigen Dokument) kein Präfix trägt.
>
> ```sql
> DELETE FROM vector_store WHERE metadata->>'library_id' = '<library-id>';
> DELETE FROM documents WHERE library_id = '<library-id>';
> DELETE FROM rss_feed_state WHERE library_id = '<library-id>';  -- nur bei RSS_FEED-Bibliotheken
> ```
>
> Anschließend die Bibliothek regulär neu indizieren. Für eine Demo-/Testinstanz mit überschaubarem
> Korpus ist ein vollständiger Re-Seed (Datenbank-Volume verwerfen, Korpus neu einspielen) statt
> eines gezielten Rücksetzens pro Bibliothek ebenfalls zulässig und oft einfacher.

> **Neuindizierung nach Einführung der Ingestion-Pipelines je Dokumenttyp (01./02.09.2026).** Vor
> diesem Update lief jedes
> zugelassene Format über denselben Weg (Tika-Extraktion, Token-Chunking). Seitdem übernehmen eigene
> Pipelines den Zuschnitt für `.pdf` (`pdf`), `.docx` (`docx`), `.pptx` (`pptx`),
> `.xlsx`/`.csv`/`.ods` (`tabular`), `.html` (`html`), `.eml`/`.msg` (`email`) sowie `.odt` (`odt`)
> und `.odp` (`odp`) — jede mit ihrer eigenen `pipeline_id`/`pipeline_version` am Chunk (siehe
> [Wissensbibliotheken](wissensbibliotheken.md), Abschnitt zu Pipeline-Versionen und Nachzug).
> `.md`, `.txt`, `.doc` sowie jedes Format ohne eigene Pipeline laufen unverändert über
> `TikaFallbackFormat` weiter — für sie ändert sich am bestehenden Bestand nichts. Für `.odt`/`.odp`
> ändert sich der Bestand dagegen genauso wie für die anderen sechs Formate: Wer diese Anleitung
> abarbeitet, muss `odt`/`odp` als eigene Aufrufe mitnehmen (siehe unten), sonst bleiben bestehende
> ODT-/ODP-Dokumente dauerhaft auf dem Fallback-Zuschnitt.
>
> Anders als bei den beiden Fällen oben ist kein bibliotheksweites Rücksetzen von `documents`/`vector_store`
> nötig: Die selektive Neuindizierung wählt gezielt über die Chunk-Metadaten aus, welcher Bestand vom
> alten, generischen Zuschnitt betroffen ist. **Der eigentliche Migrationsschritt ist ein neunter,
> vorangestellter Aufruf mit `pipelineId: "tika-fallback"`**, wiederholt bis `done: true` in der
> Antwort steht:
>
> ```bash
> curl -X POST http://localhost:8081/api/v1/admin/indexing/pipeline-reindex \
>   -H "Content-Type: application/json" \
>   -H "Authorization: Bearer <token>" \
>   -d '{"pipelineId": "tika-fallback", "belowVersion": 1, "batchSize": 10}'
> ```
>
> Dieser Aufruf wählt jeden Chunk ohne `pipeline_id`/`pipeline_version` aus (Altbestand vor
> Einführung der Pipeline-Metadaten, siehe unten) **und** jeden Chunk, den `tika-fallback` selbst in
> Version 0 erzeugt hat — beides
> wird beim Neuerzeugen über die Registry an die heute zuständige Pipeline geroutet, nicht mehr an
> `TikaFallbackFormat` zurück. Erst danach folgen, ebenfalls je wiederholt bis `done: true`, die
> acht formatbezogenen Aufrufe (`pdf`, `docx`, `pptx`, `tabular`, `html`, `email`, `odt`, `odp`) — sie
> decken nur noch den Zwischenstand ab, also Chunks, die bereits mit
> `pipeline_id`/`pipeline_version` geschrieben wurden, aber noch auf einer älteren Version ihrer
> heutigen Pipeline liegen:
>
> ```bash
> curl -X POST http://localhost:8081/api/v1/admin/indexing/pipeline-reindex \
>   -H "Content-Type: application/json" \
>   -H "Authorization: Bearer <token>" \
>   -d '{"pipelineId": "pdf", "belowVersion": 1, "batchSize": 10}'
> ```
>
> Fortschritt und Füllstand je Bibliothek (Chunks insgesamt / auf aktueller Version / darunter) sind
> jederzeit über `GET /api/v1/admin/indexing/pipeline-versions` abfragbar (`SYSTEM_ADMIN`, auf die
> eigene Organisation begrenzt). Beide Endpunkte sind authentifiziert; ein Aufruf ohne gültiges Token
> scheitert mit 401, genauso wie der Indizierungs-Endpunkt oben.
>
> **Altbestand vor Einführung der Pipeline-Metadaten (01.09.2026)** trägt weder `pipeline_id`
> noch `pipeline_version` und zählt dafür als `tika-fallback` Version 0 — er ist **ausschließlich**
> über den `tika-fallback`-Aufruf oben erfasst, nicht über die acht formatbezogenen Aufrufe: Die
> Auswahlabfrage der selektiven Neuindizierung matcht einen Chunk ohne `pipeline_id` nur gegen
> `pipelineId: "tika-fallback"` (`COALESCE` auf den Legacy-Wert), niemals gegen eine der acht
> übrigen Kennungen. Wer den `tika-fallback`-Aufruf auslässt, bekommt bei den acht formatbezogenen
> Aufrufen sofort `done: true` und hat den gesamten älteren Bestand — bei jeder heutigen
> Installation der Normalfall — nicht angefasst.
