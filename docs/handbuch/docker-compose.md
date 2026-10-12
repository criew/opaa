# Docker Compose

## Images aus GHCR

Auf dem Zielsystem wird kein Repository-Checkout benötigt — es genügt eine `docker-compose.yml`, die `image:` statt `build:` verwendet:

```yaml
services:
  backend:
    image: ghcr.io/criew/opaa-backend:0.1.0   # oder :main für den Entwicklungsstand
  frontend:
    image: ghcr.io/criew/opaa-frontend:0.1.0
```

Backend und Frontend immer mit **derselben** Version betreiben.

Aktualisieren auf den neuesten Stand des gewählten Tags:

```bash
docker compose pull && docker compose up -d
```

## Schnellstart

```bash
# 1. Umgebung konfigurieren
cp .env.docker.example .env.docker

# 2. Ablageverzeichnisse anlegen und für das Container-Konto zugänglich machen
mkdir -p documents uploads
sudo chown -R 65532:65532 uploads     # Schreibrecht: Eigentum abgeben
chmod -R o+rX documents               # Leserecht: Eigentum bleibt, wo es ist
```

Der zweite Schritt ist Pflicht: Das Backend läuft im Container unter der Kennung `65532`, ein
Verzeichnis, das Docker selbst für einen fehlenden Bind-Mount anlegt, gehört dagegen `root` — der
erste Upload schlägt dann fehl. Warum der Korpus nur Leserecht bekommt und nicht das Eigentum, der
Weg ohne `sudo` und die Lage auf einem Netzlaufwerk stehen unter [„Nicht-root-Betrieb des
Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

`.env.docker` bearbeiten. Voreingestellt sind lokal betriebene Modelle über die openai-kompatible
Schicht (der einzige Anbindungsweg) — dafür ist keine weitere Angabe in `.env.docker`
selbst nötig. Zwei sich gegenseitig ausschließende Wege stehen für den lokal betriebenen
Ollama-Server zur Wahl, den `OPAA_OPENAI_BASE_URL` standardmäßig voraussetzt (siehe
[„LLM-Anbieter"](#llm-anbieter) und [„Lokal betriebenes Ollama im
Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack) unten für Details zu beiden):

**Weg A — Ollama als Teil dieses Stacks (Compose-Profil `ollama`).** Keine weitere Konfiguration
nötig — `OPAA_OPENAI_BASE_URL` zeigt bereits auf `http://ollama:11434/v1`. Empfohlen für den
allerersten Start: zunächst nur den Init-Schritt abwarten (er lädt mehrere GiB Modelldaten herunter,
siehe „Ressourcen- und Downloadhinweis" unten), bevor der restliche Stack hochfährt — ein Dokument,
das währenddessen bereits hochgeladen wird, würde sonst mit Status `FAILED` enden, weil das
Chat-/Embedding-Modell noch fehlt:

```bash
docker compose --profile ollama up ollama-pull   # wartet, bis beide Modelle vorliegen
docker compose --profile ollama up --build       # danach den vollständigen Stack starten
```

Wer dieses Warten nicht braucht (z. B. weil ohnehin erst später indiziert wird), startet stattdessen
direkt mit `docker compose --profile ollama up --build` — der Stack läuft dann bereits, während
`ollama-pull` im Hintergrund noch lädt.

**Weg B — externer Ollama-Server** (auf dem Host oder in einem anderen Netz), ohne das
Compose-Profil. Läuft Ollama auf dem Host, in `.env.docker` setzen:

```env
OPAA_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
```

und danach starten:

```bash
docker compose up --build
```

Ohne einen erreichbaren externen Server unter der konfigurierten Adresse startet der Stack zwar,
aber Indizierung und Fragen schlagen fehl.

```bash
# 3. Anwendung öffnen
# Frontend: http://localhost:3000
# Backend-API: http://localhost:8081/api
```

## Services

| Service     | Host-Port | Container-Port | Beschreibung                                          |
|-------------|-----------|-----------------|--------------------------------------------------------|
| frontend    | 3000      | 8080            | React-App über Nginx bereitgestellt                    |
| backend     | 8081      | 8080            | Spring Boot API                                         |
| postgres    | 5432      | 5432            | PostgreSQL 18 mit pgvector                              |
| keycloak    | 8180      | 8180            | Keycloak (nur `oidc`-/`demo`-Profil)                    |
| ollama      | —         | 11434           | Lokal betriebener Ollama-Server (nur `ollama`-Profil; kein Host-Port, siehe unten) |
| ollama-pull | —         | —               | Einmaliger Init-Schritt, zieht `nomic-embed-text`/`phi3:mini` (nur `ollama`-Profil) |
| upload-store | 8095 (API), 8096 (Konsole unter `/rustfs/console/`) | 9000, 9001 | Objektspeicher der Originalablage (nur `upload-s3`-Profil; an `127.0.0.1` gebunden, siehe [Originalablage](#originalablage)) |
| upload-store-init | —    | —               | Einmaliger Init-Schritt, legt den Bucket der Originalablage an (nur `upload-s3`-Profil) |

## Konfiguration

Alle Konfigurationen erfolgen über Umgebungsvariablen in `.env.docker`. Docker Compose lädt diese Datei über die `env_file`-Direktive. Alle verfügbaren Optionen mit Beschreibungen finden Sie in `.env.docker.example`.

> **Zwei getrennte Vorlagen, zwei Zwecke.** `.env.example` ist auf `./gradlew bootRun` auf dem Host zugeschnitten (Datenbank auf `localhost`, CORS auf den Vite-Dev-Server `:5173`, kein `SPRING_PROFILES_ACTIVE`, weil `bootRun` es üblicherweise direkt in der Shell setzt). `.env.docker.example` ist die Vorlage für den Compose-Stack: `SPRING_PROFILES_ACTIVE=docker,dev` gesetzt, `OPAA_DB_URL` auskommentiert (der `docker`-Profil-Default liefert bereits den richtigen Hostnamen `postgres`), `OPAA_CORS_ALLOWED_ORIGINS` auf den Frontend-Host-Port `:3000` statt `:5173` und die leer gesetzten `OPAA_OPENAI_*`-Zeilen auskommentiert (ein leer gesetzter Wert überschreibt sonst den Anwendungs-Default, siehe Hinweis in der Variablen-Tabelle unten). Wer beide Kopien unverändert nebeneinander nutzt — `.env` für `bootRun`, `.env.docker` für Compose — bekommt in beiden Fällen einen startfähigen Stand, ohne die jeweils andere Vorlage von Hand nachzubessern.

> **Wichtig:** Docker Compose lädt automatisch eine `.env`-Datei (falls vorhanden) für die Variablen-Interpolation in `docker-compose.yml` selbst. Um Konflikte mit lokalen Entwicklungseinstellungen zu vermeiden, verwenden Docker-Compose-Services `.env.docker` als ihre `env_file`. Wenn Sie eine `.env`-Datei für die lokale Entwicklung haben, stellen Sie sicher, dass die Docker-relevanten Variablen (Ports, DB-Anmeldeinformationen) nicht kollidieren.

### Docker-spezifische Variablen

Diese Variablen sind wichtig, wenn mit Docker Compose ausgeführt wird, und sollten in `.env.docker` gesetzt werden:

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker,oidc` (Betrieb) oder `docker,dev` (Entwicklung) | Aktiviert Docker-spezifische Konfiguration (DB-URL, openai-kompatible Basis-Adresse) und den Auth-Modus; ohne `oidc` oder `dev` startet das Backend nicht |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend muss an alle Schnittstellen binden, um von anderen Containern erreichbar zu sein. `0.0.0.0` bindet unter Java IPv4 und, wo vorhanden, IPv6; `::` dagegen verhindert den Start in einem Container ohne IPv6 |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Muss mit dem Host-Port des Frontends übereinstimmen |
| `OPAA_DB_USERNAME` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |
| `OPAA_DB_PASSWORD` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |

### Minimale `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker,dev
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
OPAA_PGVECTOR_DIMENSIONS=768
```

Chat und Einbettung laufen damit über die openai-kompatible Schicht gegen einen lokal betriebenen
Ollama-Server (`OPAA_OPENAI_BASE_URL` ist im Profil `docker` auf `http://ollama:11434/v1`
vorbelegt). `OPAA_PGVECTOR_DIMENSIONS=768` gehört zwingend dazu (siehe die Zeile zu dieser Variable
in [„Alle Umgebungsvariablen"](#alle-umgebungsvariablen) unten) - ohne sie bricht die erste
Einbettung mit einem Dimensionsfehler ab, weil der Embedding-Default `nomic-embed-text` mit 768
Dimensionen einbettet, nicht mit dem Anwendungs-Default 1536. Für einen anderen Anbieter kommen
`OPAA_OPENAI_BASE_URL` und `OPAA_OPENAI_API_KEY` hinzu (überschreiben die Voreinstellung) - dann
richtet sich `OPAA_PGVECTOR_DIMENSIONS` nach dessen Embedding-Modell statt nach `nomic-embed-text`.

## Dokumente

Dokumente im `./documents`-Verzeichnis ablegen (oder `OPAA_INDEXING_DOCUMENT_PATH_HOST` in `.env.docker` ändern). Das Verzeichnis wird in den Backend-Container unter `/app/documents` gemountet.

## Datenbank

PostgreSQL-Daten werden in einem Docker-Volume (`opaa-postgres-data`) gespeichert. Daten überleben `docker compose down`- und `docker compose up`-Zyklen.

Datenbank zurücksetzen:

```bash
docker compose down -v
```

> **Hinweis:** `docker compose down -v` muss ausgeführt werden, wenn `OPAA_DB_USERNAME` oder `OPAA_DB_PASSWORD` geändert wird, weil PostgreSQL den initialen Benutzer nur beim ersten Start erstellt. Ohne Volume-Entfernung werden Anmeldeinformationsänderungen ignoriert.

> **Mit aktivem Compose-Profil `ollama` löscht `docker compose down -v` zusätzlich das
> benannte Volume `opaa-ollama-data`** — die dort gespeicherten Modelle (`nomic-embed-text`,
> `phi3:mini`, zusammen rund 2,5 GB). Ein danach erneut gestarteter Stack lädt beide Modelle über
> `ollama-pull` vollständig neu herunter, siehe [„Lokal betriebenes Ollama im Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack).

> **Nach einer Rücksicherung aus einem Dump sind zwei Schritte nachzuholen**, sobald lokale Konten im
> Einsatz sind: das Signaturgeheimnis rotieren und die Sperren der Zwischenzeit erneut setzen. Warum
> und in welcher Reihenfolge, steht unter
> [„Nacharbeit nach einer Rücksicherung der Datenbank"](#nacharbeit-nach-einer-rücksicherung-der-datenbank).

## Lokal betriebenes Ollama im Compose-Stack

Der Compose-Stack (`docker-compose.yml`) enthält einen optionalen `ollama`-Service unter dem
eigenen Compose-Profil **`ollama`** — er startet nur, wenn er ausdrücklich angefordert wird, damit
der unveränderte Stack (kein Profil oder nur `oidc`/`demo`) keinen zusätzlichen Download und keinen
zusätzlichen, ressourcenhungrigen Container voraussetzt:

```bash
docker compose --profile ollama up --build
```

Das startet zusätzlich zu den üblichen Services zwei weitere:

- **`ollama`** — der eigentliche Ollama-Server, mit einem benannten Volume (`opaa-ollama-data`) für
  die heruntergeladenen Modelle. Nicht auf einen Host-Port veröffentlicht: Backend und der
  Init-Schritt unten erreichen ihn ausschließlich über das Compose-Netz unter seinem Servicenamen
  (`ollama`) — genau der Hostname, auf den `OPAA_OPENAI_BASE_URL` im Spring-Profil `docker` bereits
  standardmäßig zeigt (siehe oben). Es sind deshalb **keine** Umgebungsvariablen-Änderungen nötig,
  um dieses Profil zu nutzen.
- **`ollama-pull`** — ein einmaliger Init-Schritt, der `nomic-embed-text` (Embedding-Default,
  `OPAA_OPENAI_EMBEDDING_MODEL`) und `phi3:mini` (Chat-Default, `OPAA_OPENAI_CHAT_MODEL`) zieht,
  sobald `ollama` bereit ist, und danach beendet wird. Beide Modelle sind nötig, nicht nur das
  Embedding-Modell: `io.opaa.llm.LlmModelSeeder` übernimmt `phi3:mini` beim allerersten
  Backend-Start als aktives, verwaltetes Chat-Modell (siehe [„LLM-Anbieter"](#llm-anbieter) oben) —
  ohne es schlägt die erste Chat-Anfrage fehl, obwohl `ollama` selbst bereits läuft. Der Pull-Schritt
  ist **idempotent**: `ollama pull` vergleicht gegen das, was `ollama` bereits im Volume
  `opaa-ollama-data` gespeichert hat, und lädt bei einem erneuten Start desselben Stacks nichts noch
  einmal herunter.

**Ressourcen- und Downloadhinweis:** Beide Modelle zusammen laden beim allerersten Start mehrere
Gigabyte herunter (`phi3:mini` rund 2,2 GiB, `nomic-embed-text` rund 274 MiB, jeweils komprimiert)
und benötigen anschließend entsprechend Plattenplatz im Volume sowie Arbeitsspeicher/CPU (oder GPU)
für den laufenden Ollama-Server. Mit `docker compose logs -f ollama-pull` lässt sich der
Download-Fortschritt verfolgen; der Stack selbst ist bereits nutzbar, sobald `ollama` gesund ist
(`ollama` beantwortet dann `ollama list`), auch während `ollama-pull` noch lädt — die erste
Indizierung/Chat-Anfrage kann in diesem Fenster aber noch fehlschlagen, bis beide Modelle vorliegen.

**Nur mit Registry-Zugriff, auch bei bereits vorhandenen Modellen:** `ollama pull` prüft bei jedem
Aufruf gegen die Ollama-Registry im Internet, ob die lokal gespeicherten Layer noch aktuell sind —
das macht den Schritt idempotent (siehe oben), setzt aber Netzzugang zu dieser Registry voraus. In
einem abgeschotteten Netz ohne diesen Zugang **exitet `ollama-pull` mit einem Fehler, obwohl beide
Modelle bereits im Volume liegen**. Betroffen ist jeder erneute Start, nicht nur der erste. Für einen
solchen Betrieb entweder das `command` in `docker-compose.yml` um einen vorangestellten
`ollama list | grep -q ... ||`-Kurzschluss ergänzen (überspringt den Netzzugriff, wenn beide Modelle
bereits vorhanden sind) oder `ollama-pull` bei einem erneuten Start ganz weglassen
(`docker compose --profile ollama up ollama backend frontend postgres`, ohne `ollama-pull` in der
Serviceliste).

**Ein fehlgeschlagener `ollama-pull` bleibt sonst unauffällig:** Exitet der Init-Schritt mit einem
Fehler (Registry nicht erreichbar wie oben, Plattenplatz erschöpft, o. ä.), laufen `backend` und
`frontend` trotzdem unbeeindruckt weiter — der Stack wirkt oberflächlich normal gestartet, nur
Indizierung/Chat schlagen dann fehl, weil ein oder beide Modelle fehlen. Ursache und Fehlermeldung
stehen in `docker compose logs ollama-pull`; das ist der erste Blick bei einer sonst unerklärten
`FAILED`-Indizierung direkt nach `docker compose --profile ollama up`.

**GPU-Nutzung:** Ohne eigene Ergänzung nutzt `ollama` ausschließlich die CPU — `docker-compose.yml`
enthält keinen `deploy.resources.reservations.devices`-Eintrag für eine GPU. Wer eine NVIDIA-GPU
mit installiertem Container-Toolkit durchreichen will, ergänzt einen solchen Eintrag am
`ollama`-Service selbst (analog zum auskommentierten `ports`-Eintrag dort) - nicht Teil dieser
Voreinstellung, weil sie ein GPU-fähiges Docker-Setup auf dem Host voraussetzt, das nicht jede
Installation hat.

**Abwägung Imagegröße/Ressourcen vs. „ein Befehl, alles läuft":** Das `ollama`-Image selbst ist mit
rund 3,4 GB (amd64) bereits größer als beide Modelle zusammen (rund 2,5 GB, siehe oben) — Image und
Modelle zusammen machen den gesamten zusätzlichen Umfang dieses Profils aus, nicht nur Letztere. Wer
keinen lokal betriebenen Ollama-Server braucht oder bereits einen extern betreibt, bleibt beim
unveränderten Standard (kein Profil) und spart sich Download und laufenden Container vollständig —
deshalb ist `ollama` ein eigenes, nicht standardmäßig aktives Profil und keine Voreinstellung.

**Alternative: externer Ollama-Server statt des Compose-Profils.** Läuft Ollama bereits auf dem
Host oder in einem anderen Netz, ist das Profil `ollama` nicht nötig — stattdessen die Zieladresse
auf den externen Server umbiegen, z. B. bei einer Installation auf dem Host selbst:

```env
OPAA_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
```

(`docker-compose.yml` setzt den dafür nötigen `extra_hosts`-Eintrag für den `backend`-Service
bereits.) Beide Wege sind gleichwertig — das Compose-Profil ist der bequemere Einstieg ohne
Ersteinrichtung außerhalb des Stacks, ein externer Ollama-Server eignet sich besser, wenn er von
mehreren Stacks/Installationen gemeinsam genutzt werden soll oder bereits vorhanden ist.

Kein Port-Expose über `127.0.0.1` hinaus: `ollama` veröffentlicht standardmäßig **keinen** Port; der
in `docker-compose.yml` auskommentierte `ports`-Eintrag für den direkten Host-Zugriff bindet, falls
aktiviert, ebenfalls nur auf `127.0.0.1` (siehe die dortigen Kommentare und [„Härtung für erreichbare
Deployments"](#härtung-für-erreichbare-deployments), Punkt 5).

## Der mitgelieferte Objektspeicher im Compose-Stapel

Der Stapel bringt einen Objektspeicher für die Originalablage mit, hinter dem eigenen Compose-Profil
`upload-s3`. Ohne dieses Profil existieren die beiden Dienste nicht und der Stapel verhält sich
unverändert.

```bash
docker compose --profile upload-s3 up -d upload-store upload-store-init
```

- `upload-store` hält die Originale und hat ein **eigenes benanntes Volume**. Ohne dieses Volume
  wäre der Bestand beim Neuaufsetzen weg, während die Dokumentzeilen in der Datenbank bestehen
  bleiben und auf nicht mehr vorhandene Objekte zeigen — derselbe Fallstrick wie beim
  Verzeichnisweg ohne eingehängtes Verzeichnis. `docker compose down -v` löscht dieses Volume
  mitsamt allen Originalen; für den regulären Stopp genügt `docker compose down`.
- `upload-store-init` läuft einmal, legt den Bucket an und beendet sich. Er schreibt keine Objekte;
  der Inhalt des Buckets gehört ab da allein der Anwendung.
- Das Backend hängt **nicht** von beiden ab: Eine Installation, die beim Verzeichnisweg bleibt,
  behält ihre Startreihenfolge, und ein kurz nicht erreichbarer Speicher hält den Start nicht auf.

Dieser Dienst ist nicht der Objektspeicher des Demo-Profils. Jener füllt einen Korpus-Bucket, aus
dem eine Bibliothek als **Quelle** liest, und wird bei jedem Start neu befüllt; er hat deshalb
bewusst kein Volume. Beide Profile lassen sich nebeneinander betreiben.

In `.env.docker` gehören dann die fünf Werte, mit denen das Backend den Dienst anspricht — Ablage,
Endpunkt, Bucket und die beiden Zugangsschlüssel (`OPAA_UPLOAD_STORE`, `OPAA_UPLOAD_S3_ENDPOINT`,
`OPAA_UPLOAD_S3_BUCKET`, `OPAA_UPLOAD_S3_ACCESS_KEY`, `OPAA_UPLOAD_S3_SECRET_KEY`); die Vorlage
`.env.docker.example` trägt sie auskommentiert und mit genau den Werten, die der Dienst selbst
voreingestellt hat. Die Zugangsdaten des Dienstes **selbst** setzt Docker Compose ein und liest sie
deshalb nie aus `.env.docker`, sondern nur aus der Prozessumgebung oder einer `.env`-Datei
(`OPAA_UPLOAD_STORE_ROOT_USER`, `OPAA_UPLOAD_STORE_ROOT_PASSWORD`, `OPAA_UPLOAD_STORE_BUCKET`, siehe
[Alle Umgebungsvariablen](#alle-umgebungsvariablen)). Wer sie ändert, ändert sie auf beiden Seiten —
sonst weist der Speicher jeden Zugriff zurück.

Der Endpunkt zeigt auf den Servicenamen im Compose-Netzwerk. Er passiert die Zieladressprüfung der
Originalablage immer, weil er aus der Betriebskonfiguration stammt; ein Eintrag in einer Freigabeliste
ist dafür nicht nötig, und die Freigabeliste der Konnektoren ist davon unberührt.

## Mailpit im lokalen Stapel

Für Demonstration, Entwicklung und die E2E-Suite liegt dem Compose-Stapel ein Mailserver bei, der
alles annimmt und nichts zustellt:

```bash
docker compose --profile mail up -d mailpit
```

Die abgefangenen Nachrichten liegen danach unter `http://localhost:8025`. In der Verwaltung
einzutragen: Server `mailpit`, Port `1025`, Verschlüsselung `NONE`, keine Zugangsdaten. Ohne das
Profil `mail` existiert der Dienst nicht, und der Stapel verhält sich unverändert.

## Nicht-root-Betrieb des Backend-Containers

Das Backend läuft im Container als Benutzer und Gruppe `65532` — nicht als `root`. Es lauscht auf
Port 8080, also oberhalb des privilegierten Bereichs, und schreibt nach `/app/uploads`, nach `/tmp`
und in ein etwaig konfiguriertes Temp-Verzeichnis; gelesen wird außerdem, was eingehängt ist.

Bei einem Bind-Mount gilt der Eigentümer des **Host**-Verzeichnisses, nicht der des Verzeichnisses
im Image — Docker ändert daran beim Einhängen nichts. Daraus folgen drei Betriebsanforderungen, eine
für jede Art von eingehängtem Verzeichnis:

1. **`/app/uploads` muss für `65532` beschreibbar sein** — am einfachsten, indem das
   Host-Verzeichnis dieser Kennung gehört. Gilt, solange die Originale im Dateisystem liegen
   (`OPAA_UPLOAD_STORE` nicht auf `s3`).
2. **Ein konfiguriertes Temp-Verzeichnis muss für `65532` beschreibbar sein.** Betrifft
   `OPAA_UPLOAD_S3_TEMP_DIRECTORY` und `OPAA_INDEXING_S3_TEMP_DIRECTORY`: Beide zeigen ohne Angabe
   auf `/tmp` im Container, das im Image für alle beschreibbar ist — wer sie aber, wie in der
   Variablentabelle empfohlen, auf ein großes eingehängtes Volume legt, muss dieses Volume
   übergeben. Sonst scheitert jeder Upload beim Anlegen der Arbeitsdatei, auch mit
   `OPAA_UPLOAD_STORE=s3`.
3. **Jedes weitere eingehängte Verzeichnis muss für `65532` les- und betretbar sein.** Das ist die
   Anforderung, die am leichtesten übersehen wird: Als `root` lief der Prozess an jeder
   Leseberechtigung vorbei. Betroffen sind `/app/documents` und jedes über
   `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` freigegebene Verzeichnis einer FILESYSTEM-Bibliothek. Eine
   gehärtete Freigabe (`root:root 0700`, oder ein CIFS-Mount mit `uid=0,file_mode=0600`) war als
   `root` lesbar und ist es jetzt nicht mehr — siehe [Konnektor
   Dateisystem](konnektoren/filesystem.md), Abschnitt 3.

Der häufigste Fall ist der erste. Legt Docker das Verzeichnis beim Start selbst an, weil es fehlt,
gehört es `root`, und der erste Upload endet mit `500` und dieser Meldung im Protokoll:

```text
java.io.UncheckedIOException: Datei konnte nicht gespeichert werden
Caused by: java.nio.file.AccessDeniedException: /app/uploads/<Organisations-ID>
```

Der Fehler tritt erst beim ersten Upload auf, nicht beim Start: Der Container läuft, die
Zustandsübersicht meldet `UP`.

**Neue Installation.** Vor dem ersten `docker compose up` (Schritt 2 im
[Schnellstart](#schnellstart)):

```bash
mkdir -p documents uploads
sudo chown -R 65532:65532 uploads     # Schreibrecht: Eigentum abgeben
chmod -R o+rX documents               # Leserecht: Eigentum bleibt, wo es ist
```

**Bestehende Installation.** Dieselben beiden Zeilen ohne `mkdir`, im Verzeichnis mit der
`docker-compose.yml` — sie ändern Rechte und Eigentümer, keine Inhalte, und sind damit ohne
Datenverlust ausführbar:

```bash
docker compose down
sudo chown -R 65532:65532 uploads
chmod -R o+rX documents
docker compose pull && docker compose up -d
```

**Warum `documents` nicht mit übereignet wird.** Der Korpus wird von OPAA nur gelesen, aber
typischerweise von jemand anderem befüllt — einem Fachverfahren, einem Sync-Dienst, einer Freigabe.
Ein `chown` auf `65532` nähme genau diesem Schreiber die Rechte. Gebraucht wird Lese- und
Betretungsrecht für „andere", und das ist bei den üblichen `0755` bereits da; `chmod -R o+rX` oben
ist nur für einen enger gesetzten Korpus nötig und lässt das `x`-Bit dabei den Verzeichnissen
vorbehalten. Wo auch das nicht in Frage kommt, ist eine gemeinsame Gruppe oder der `user:`-Ausweg
unten der Weg. Dasselbe gilt für jedes über `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` freigegebene
Verzeichnis.

**Ohne `sudo`, mit eigener Kennung.** Wer die Verzeichnisse nicht abgeben kann oder darf — etwa auf
einem Host mit einem Dienstkonto, dem die Daten gehören sollen, oder auf einem Netzlaufwerk, auf dem
`chown` nicht wirkt —, lässt den Container stattdessen unter genau dieser Kennung laufen. In der
`docker-compose.yml` steht die Zeile beim Backend-Service bereits auskommentiert:

```yaml
services:
  backend:
    user: "1000:1000" # die eigene uid:gid, `id -u`/`id -g`
```

Das ist die Ausweichmöglichkeit, nicht der empfohlene Weg: Die Kennung muss dann bei jedem
Umzug und in jeder abgeleiteten Compose-Datei mitgepflegt werden, während `65532` eine feste
Eigenschaft des Images ist. `root` (`user: "0:0"`) erfüllt denselben Zweck und gibt den
Sicherheitsgewinn wieder auf. Bei einem **CIFS/SMB-Mount** ist dieser Weg oder die Mount-Option
`uid=65532,gid=65532` sogar der einzige: Die Rechte eines solchen Einhängepunkts stehen in den
Mount-Optionen, ein `chown` darauf läuft ins Leere.

**Benannte Volumes brauchen nichts davon — und vertragen sich nicht mit `user:`.** Wer statt eines
Bind-Mounts ein benanntes Volume auf `/app/uploads` legt, muss nichts tun: Docker übernimmt beim
ersten Einhängen eines leeren Volumes die Eigentümerverhältnisse des Verzeichnisses aus dem Image,
und das gehört bereits `65532`. Genau deshalb schließen sich die beiden Wege aus: Ein so
übernommenes Volume gehört `65532` und ist für einen Container mit `user: "1000:1000"` dann gerade
**nicht** beschreibbar — wer `user:` setzt, bleibt beim Bind-Mount. Beim Objektspeicher
(`OPAA_UPLOAD_STORE=s3`, siehe [Originalablage](#originalablage)) entfällt Anforderung 1 ganz; die
Anforderungen 2 und 3 bleiben, sobald ein eigenes Temp-Verzeichnis oder eine FILESYSTEM-Bibliothek
im Spiel ist.

`docker cp` bleibt in beiden Richtungen nutzbar (siehe [„Diagnose ohne
Shell"](#diagnose-ohne-shell)): Es läuft über den Docker-Daemon und nicht unter der Kennung des
Containers. Ebenso unberührt sind Thread- und Heap-Dump nach `/tmp` — das Verzeichnis ist im Image
für alle beschreibbar — und ein `docker exec` auf `jcmd`, das ohne `--user` unter derselben Kennung
`65532` läuft wie der Prozess und sich deshalb an ihn anhängen darf.

## Eigene interne CA ergänzen

> **Wichtig:** `-Djavax.net.ssl.trustStore=…` **ersetzt** den mitgelieferten Truststore, es ergänzt
> ihn nicht. Wer nur seine interne CA in einen frischen Store legt und darauf zeigt, verliert
> sämtliche öffentlichen CAs — HTTPS zu LLM-Anbietern, S3 und einem OIDC-Anbieter mit öffentlichem
> Zertifikat scheitert dann mit `PKIX path building failed`, ohne erkennbaren Zusammenhang zur
> Änderung.

Richtig ist, den mitgelieferten Store zu kopieren, zu ergänzen und zurückzumounten:

```bash
# 1. Mitgelieferten Truststore herausholen (funktioniert ohne Shell im Container)
docker cp <container>:/opt/java/openjdk/lib/security/cacerts ./cacerts

# 2. Eigene CA ergänzen (Standardpasswort des JDK-Truststores: changeit)
keytool -importcert -keystore ./cacerts -storepass changeit \
        -alias interne-ca -file interne-ca.crt -noprompt

# 3. Ergänzte Kopie an ihren Originalplatz mounten - dann ist kein JVM-Schalter nötig
```

```yaml
services:
  backend:
    volumes:
      - ./cacerts:/opt/java/openjdk/lib/security/cacerts:ro
```

Die Kopie muss für die Kennung `65532` **lesbar** sein, unter der das Backend läuft — bei einer
restriktiven `umask` beim Erzeugen (etwa `077`) ist sie es nicht, und der Start bricht mit einer
Meldung über den Truststore ab. Ein `chmod 644 ./cacerts` genügt; siehe [„Nicht-root-Betrieb des
Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

Der Schritt ist nach einem Image-Update zu wiederholen, sobald die Laufzeit neue öffentliche
Wurzelzertifikate mitbringt: Die gemountete Kopie überdeckt den Stand des Images.

## Diagnose ohne Shell

```bash
docker compose logs -f backend                       # Logs
docker cp <container>:/app/uploads ./uploads-kopie   # Dateien aus dem Container holen
docker run --rm -it --network container:<container> nicolaka/netshoot   # Netzwerkdiagnose im selben Netz
```

> **`docker cp … /app/uploads` gilt nur für die Dateisystem-Ablage.** Mit `OPAA_UPLOAD_STORE=s3`
> liegen die hochgeladenen Originale im Objektspeicher; das Verzeichnis im Container ist dann leer
> oder trägt nur noch einen Altbestand aus der Zeit davor, und der Befehl kopiert genau das, ohne
> Hinweis. Wie ein Original in diesem Fall greifbar wird, steht unter
> [Originalablage](#originalablage).

Thread- und Heap-Dump ohne Shell:

```bash
docker exec <container> /opt/java/openjdk/bin/jcmd 1 Thread.print
docker exec <container> /opt/java/openjdk/bin/jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp <container>:/tmp/heap.hprof ./heap.hprof
docker run --rm -v <projekt>_opaa-backend-tmp:/t busybox rm -f /t/heap.hprof   # Dump im Container entfernen
docker kill -s QUIT <container>   # Alternative: Thread-Dump ins Log, braucht nichts im Image
```

Der Dump muss mit absolutem Pfad unter `/tmp` liegen, weil das übrige Dateisystem nur lesbar ist.
Er enthält Geheimnisse und Inhalte und bleibt im Volume `opaa-backend-tmp` liegen, bis er entfernt
wird; deshalb die dritte Zeile.

`docker kill -s QUIT` beendet den Container **nicht** — die JVM behandelt `SIGQUIT` als Aufforderung,
einen Thread-Dump auf die Standardausgabe zu schreiben, er landet also in `docker compose logs`.

Ein `docker compose logs backend` beantwortet die überwiegende Mehrheit der Fälle, in denen früher
eine Shell benutzt wurde. Für alles Weitere lässt sich ein Werkzeug-Container in denselben Netzwerk-
oder PID-Namensraum hängen (`--network container:…`, `--pid container:…`), ohne dass das
Laufzeitimage selbst Werkzeuge mitbringen muss.

## Härtung des Compose-Stapels

Der mitgelieferte Compose-Stack (`docker-compose.yml`, `keycloak/realm-export.json`) ist ausdrücklich für
die **lokale Entwicklung** gebaut, wie der Warnhinweis am Kopf von `docker-compose.yml` sagt. Seine
Vorgabewerte sind dafür richtig gewählt — bequem, ohne Ersteinrichtung nutzbar, mit einem funktionierenden
OIDC-Realm ab dem ersten Start. Für eine **öffentlich oder auch nur aus einem größeren Netz erreichbare
Instanz** ist jeder dieser Vorgabewerte ein Problem, und der Warnhinweis allein sagt nicht, was konkret zu
tun ist. Dieser Abschnitt schließt genau diese Lücke — die Doku, nicht der Betrieb. Die öffentliche
Demo-Instanz des Projekts ist ein so gehärtetes Beispiel: alle Container-Ports auf `127.0.0.1`, ein
TLS-terminierender nginx auf dem Host davor, Keycloak unter dem Unterpfad `/idp`, der Entwicklungsnutzer
`testuser` deaktiviert, das Passwort des Administrationskontos nach jedem Seed-Lauf rotiert, der
Client `opaa-seed` nur für die Dauer eines Seed-Laufs aktiviert und das Dienstkonto `opaa-directory`
des Verzeichnisabgleichs mit einem eigenen, zufällig erzeugten Geheimnis statt des Vorgabewerts.

Die folgende Liste geht die neun tatsächlich im Repository liegenden Vorgabewerte durch. Wo eine
Gegenmaßnahme **zwingend** ist, steht das dabei; alles andere ist eine Empfehlung, deren Unterlassung
begründet werden sollte, kein hartes Muss.

| # | Fundstelle | Vorgabewert | Risiko bei erreichbarem Betrieb | Gegenmaßnahme |
|---|---|---|---|---|
| 5 | `docker-compose.yml:20`, `43`, `59`, `77` | `postgres` und `backend` (8081) binden auf `127.0.0.1`; `frontend` (3000) und `keycloak` (8180) veröffentlichen ihre Ports ohne Adressangabe und binden damit auf allen Schnittstellen | Auf `127.0.0.1` gebunden schützt vor Zugriff aus dem Netz, aber nicht vor jedem anderen Prozess und Nutzerkonto auf demselben Host (beim Backend-Port wäre eine direkte Verbindung aus dem Netz zudem der Weg, `X-Forwarded-For` am Frontend-nginx vorbei zu setzen). Die übrigen zwei Ports sind dagegen aus dem Netz erreichbar, sobald keine Firewall davorsteht — bei `keycloak` ist das der konkrete Ausnutzungsweg zu Punkt 3: Die Admin-Konsole wäre netzweit ansprechbar, unabhängig davon, ob ein vorgelagerter Reverse-Proxy nur bestimmte Pfade durchreicht | Hinter einem Reverse-Proxy alle Ports auf `127.0.0.1:` binden, so wie es `postgres` und `backend` bereits vormachen — für `keycloak` **zwingend** (sonst bleibt die Admin-Konsole trotz Proxy direkt aus dem Netz erreichbar), für `frontend` **empfohlen** (der Reverse-Proxy ist dann der einzige Weg). Für `postgres` **empfohlen**, die `ports:`-Zuordnung für den erreichbaren Betrieb ganz zu entfernen statt sie nur auf Loopback zu binden — Backend und `postgres` erreichen sich ohnehin über das interne Compose-Netz (Servicename `postgres`), ein Host-Port wird dafür nicht gebraucht. Für lokale Entwicklung (Anschluss mit einem Datenbank-Client vom Host aus, direkter Aufruf der Admin-Konsole) bleiben die bisherigen Bindungen dagegen sinnvoll — deshalb sind sie dort nicht als Fehler markiert |
| 7 | `docker-compose.yml` (Service `upload-store`, nur Compose-Profil `upload-s3`) | Root-Zugangsdaten des mitgelieferten Objektspeichers der Originalablage als Compose-Vorgabe (`opaa-uploads`/`OpaaUploads!2026`), dieselben Werte auskommentiert in `.env.docker.example` | Wer das Profil in einem erreichbaren Betrieb nutzt und die Vorgabe behält, schützt die Originale **aller** hochgeladenen Dokumente mit Zugangsdaten, die im Repository stehen. Die S3-API ist zwar nur an `127.0.0.1` gebunden — das schützt vor dem Netz, aber nicht vor anderen Prozessen und Konten auf demselben Host | **Zwingend**, sobald das Profil außerhalb einer Erprobung läuft: eigene Werte über `OPAA_UPLOAD_STORE_ROOT_USER`/`OPAA_UPLOAD_STORE_ROOT_PASSWORD` in `.env` oder der Prozessumgebung setzen (nicht in `.env.docker`, siehe Punkt 3) und dieselben Werte als `OPAA_UPLOAD_S3_ACCESS_KEY`/`OPAA_UPLOAD_S3_SECRET_KEY` in `.env.docker` eintragen. Der mitgelieferte Dienst ist als Erprobungs- und Umstellungsziel gedacht; im erreichbaren Betrieb ist ein hauseigener Objektspeicher mit eigenen Zugangsdaten, Verschlüsselung ruhender Daten und Versionierung der Regelfall (siehe [„Originalablage"](#originalablage)) |

**Es gibt keine separate `docker-compose.prod.yml`.** Mehrere der Fundstellen verlangen eine
Änderung an `keycloak/realm-export.json` (kein Compose-Override kann Inhalte einer importierten
JSON-Datei patchen, ohne selbst wieder ein Geheimnis oder einen Generierungsschritt einzuführen), einen
echten Bootstrap-Vorgang (Passwort **nach** der Ersteinrichtung ändern) bzw. eine externe, persistente
Datenbank für Keycloak (eine Betriebsentscheidung, keine Compose-Konstante). Eine zweite Compose-Datei
würde nur die Port-Bindungen automatisieren und für den Rest auf genau diese Anleitung verweisen.

## Aktualisierung auf einen neuen `main`-Stand

Die CI baut bei jedem Push auf `main` — und unabhängig davon einmal wöchentlich montags — neue
`ghcr.io/criew/opaa-backend`- und `ghcr.io/criew/opaa-frontend`-Images und veröffentlicht sie mit den
Tags `main` und `sha-<commit>` in der GHCR-Registry (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr) oben).
Der wöchentliche Lauf ändert den Code nicht, wohl aber den Digest: Er zieht die inzwischen
veröffentlichten OS-Sicherheitsupdates nach. Ein `docker compose pull` bringt deshalb auch dann einen
neuen Stand, wenn seit dem letzten Mal nichts committet wurde.

Ein Deployment-Skript, das genau das tut — die aktuellen Images ziehen und den Stack auf den neuen
Stand bringen —, ist eine naheliegende Automatisierung für eine erreichbare Instanz; die öffentliche
Demo-Instanz des Projekts läuft so, mit einem täglichen Cron-Aufruf und rotiertem Protokoll.
Ein solcher Wrapper sollte einen Schalter, der zusätzlich die Volumes verwirft, klar von der
regulären Aktualisierung trennen — damit ist die Datenbank und mit ihr der gesamte Index weg, das ist
also kein Aktualisierungs-, sondern ein Neuaufsetzschritt; danach ist zwingend eine vollständige
Neuindizierung nötig.

### Vorbereitungsschritte für Bestandsinstallationen

Welche Schritte ein Release verlangt, nennen seine Notizen im Abschnitt „Vorbereitung für
Bestandsinstallationen“ (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr));
für Einzelheiten verweisen sie auf dieses Kapitel. Den Ablauf unter Kubernetes beschreibt das
Kapitel [Kubernetes](kubernetes.md#aktualisierung-und-rückweg).

**Ein Update auf einen Stand mit lokaler Benutzerverwaltung verlangt eine Vorbereitung.** Die
Punkte 1 bis 4 werden einmalig in der `.env.docker` erledigt; ohne sie startet das Backend nicht
mehr. Punkt 5 betrifft nicht die Konfiguration, sondern die Eigentumsverhältnisse auf dem Host —
ohne ihn startet das Backend zwar, aber der erste Upload schlägt fehl. Punkt 6 betrifft die
Compose-Datei selbst — ohne ihn ist die Oberfläche nicht mehr erreichbar. Alle Schritte gehören
**vor** das `docker compose up -d`:

1. **`OPAA_AUTH_JWT_SECRET` setzen — Pflicht.** Das Wurzelgeheimnis des anwendungseigenen
   Ausstellers. Im Betriebsmodus `oidc` bricht der Start ohne es ab, ebenso bei weniger als 32
   Zeichen oder einem erkannten Platzhalter (`change_me`, `secret`, …). Erzeugen mit:

   ```bash
   openssl rand -base64 48
   ```

   Der Wert gehört zu den Geheimnissen der Installation: Eine Rotation beendet alle lokalen
   Sitzungen und entwertet alle offenen Einladungs-, Rücksetz- und Übergabelinks.

2. **`OPAA_INITIAL_ADMIN_EMAIL` auf ein zustellbares Postfach setzen — Pflicht, sobald ein
   Systemverwalterkonto gebraucht wird.** Der frühere Vorgabewert `admin@opaa.local` wird abgelehnt;
   OPAA legt dann kein Konto an und sagt das im Log. Am besten ein Funktionspostfach der IT. Siehe
   [„Erststart und Systemverwalter-Konto"](#erststart-und-systemverwalter-konto) unten — auf einer
   Bestandsinstallation entsteht das Konto **ohne** Passwort und wird bei Bedarf über die
   Notfallprozedur aktiviert.

3. **`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` setzen — dringend empfohlen, sobald ein Reverse-Proxy vor
   dem Backend steht**, und der nginx des Frontend-Containers ist bereits einer. Eingetragen wird die
   **feste Adresse des Frontend-Containers als `/32`**, nicht das ganze Compose-Netz;
   `.env.docker.example` zeigt den Wert. Ohne ihn teilen alle Clients einen Zähler: Die Anmeldegrenze
   gilt dann für das ganze Haus, und die Netzbeschränkung lokaler Systemverwalter sieht nur den Proxy.
   Der Start schreibt bei leerem Wert eine Warnung mit genau dieser Folge ins Log. Einzelheiten unter
   [„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments).

4. **`OPAA_PUBLIC_BASE_URL` setzen — empfohlen, Pflicht für Einladungen per Mail.** Die öffentliche
   Adresse der Installation; sie steht in jedem Link einer Mail. Ohne sie lassen sich „Passwort
   vergessen" und Selbstregistrierung nicht einschalten, und Einladungslinks werden zur Übergabe
   angezeigt statt versendet. Siehe [„E-Mail-Versand (SMTP)"](#e-mail-versand-smtp). Ein Zugang mit
   der Anmeldeart OAuth braucht sie ebenfalls: Der Anbieter schickt die Person nach der Zustimmung an
   `{OPAA_PUBLIC_BASE_URL}/connections/callback` zurück (siehe [„Netzwerkzugang"](#netzwerkzugang)).

5. **`uploads/` der Kennung `65532` übergeben — Pflicht, solange die Originale im Dateisystem
   liegen** (`OPAA_UPLOAD_STORE` nicht auf `s3`). Das Backend läuft im Container nicht mehr als
   `root`; ein Verzeichnis, das bisher `root` gehörte, ist für es dann nicht mehr beschreibbar, und
   der erste Upload nach dem Update endet mit `500`. Der Schritt ändert nur Eigentümer, keine
   Inhalte:

   ```bash
   sudo chown -R 65532:65532 uploads
   ```

   **Ebenfalls prüfen, weil `root` bisher jede Leseberechtigung umging:** ob `documents/`, jedes
   über `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` freigegebene Verzeichnis und ein eigenes
   `OPAA_UPLOAD_S3_TEMP_DIRECTORY` für `65532` zugänglich sind. Ein eigenes
   `OPAA_UPLOAD_S3_TEMP_DIRECTORY` oder `OPAA_INDEXING_S3_TEMP_DIRECTORY` muss zudem auf einem
   eingehängten Volume oder unter `/tmp` liegen: Das übrige Dateisystem des Containers ist nur lesbar.
   Ein Verstoß zeigt sich erst beim ersten Upload bzw. beim ersten Lauf einer S3-Bibliothek, nicht
   beim Start. Einzelheiten, der Weg ohne `sudo`,
   die Lage auf einem Netzlaufwerk und die Fehlermeldungen, an denen man es erkennt, stehen unter
   [„Nicht-root-Betrieb des Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

6. **Containerport des Frontends von `80` auf `8080` umstellen — Pflicht für jede eigene
   Compose-Datei.** Der Frontend-Container läuft nicht mehr als `root` und lauscht deshalb auf
   `8080`. Eine Compose-Datei, die nicht aus diesem Repository stammt, etwa nach
   [„Deployment aus vorgebauten Images"](#deployment-aus-vorgebauten-images-ghcr), bildet noch auf
   `80` ab. Nach dem nächsten Pull antwortet der Host-Port dann nicht mehr, und ein vorgeschalteter
   Proxy meldet `502`. Umzustellen sind:

   - die Port-Abbildung des Dienstes `frontend`, etwa `"127.0.0.1:3000:80"` zu
     `"127.0.0.1:3000:8080"`,
   - ein eigener Proxy, der den Container im Docker-Netz direkt anspricht, von `frontend:80` auf
     `frontend:8080`.

   Einzelheiten stehen unter [„Frontend-Laufzeitimage"](#frontend-laufzeitimage).

Was sich **nicht** ändert: Bestehende Konten, Rollen, Rechte und der Index bleiben, wie sie sind. Wer
bisher einen Identitätsanbieter betreibt, betreibt ihn unverändert weiter; die lokale
Benutzerverwaltung bleibt ausgeschaltet, bis sie jemand einschaltet. Die eine inhaltliche Änderung am
Bestand: **`OPAA_INITIAL_ADMIN_EMAIL` macht kein Konto eines Identitätsanbieters mehr zum
Systemverwalter** — bereits vergebene Rollen bleiben, die Regel griff nur beim Anlegen eines Kontos.

Ohne ein solches Skript entspricht der Ablauf diesen Schritten, ausgeführt im Verzeichnis mit der `docker-compose.yml`:

```bash
docker compose pull          # neue Images aus GHCR holen
docker compose up -d         # Container mit den neuen Images neu erstellen
docker image prune -f        # optional: verdrängte Images aufräumen
```

`docker compose up -d` ersetzt nur die Container, deren Image sich geändert hat; die übrigen laufen weiter. Ein `docker compose restart` genügt **nicht** — es verwendet den vorhandenen Container samt altem Image erneut.

Zustand danach prüfen:

```bash
docker compose ps
docker compose logs -f backend
```

> **Service- statt Containernamen verwenden.** Alle Befehle oben sprechen den **Servicenamen** aus der Compose-Datei an (`backend`, `frontend`, `postgres`), nicht einen Containernamen. Das ist der robustere Weg: Wie die Container tatsächlich heißen, hängt davon ab, ob die jeweilige Compose-Datei `container_name` setzt und wie das Compose-Projekt heißt — beides unterscheidet sich zwischen der Testinstanz, lokalen Entwicklungsstacks und der E2E-Suite. `docker compose logs backend` funktioniert in allen dreien, `docker logs <name>` nur in einem.

### Migrationen aus älteren Ständen

**Neu aufsetzen: Datenbank-Baseline je Modul (seit Ende September 2026).** OPAA legt sein Schema mit
Liquibase an und verzeichnet in der Tabelle `databasechangelog`, welche Changesets gelaufen sind. Die
Historie ist zu einer neuen Baseline zusammengefasst, und die Changesets liegen in einem Verzeichnis
je Modul (`db/changelog/foundation/` bis `db/changelog/external/`). Zu einer Datenbank, die vor diesem
Stand angelegt wurde, passt diese Liste nicht mehr: Liquibase spielte die Baseline in eine Datenbank
ein, deren Tabellen schon existieren, und das Backend startet nicht. Das gilt auch für eine
Installation, die erst mit der zusammengefassten Baseline angelegt wurde, solange diese noch in einer
einzigen Datei lag. **Eine solche Installation lässt sich nicht aktualisieren, sondern nur neu
aufsetzen.** Ob sie betroffen ist, zeigt vor dem Update:

```bash
docker compose exec postgres psql -U opaa -d opaa -tAc \
  "SELECT count(*) FROM databasechangelog WHERE id = 'foundation-2026-09-27-baseline'
     AND author = 'opaa' AND filename = 'db/changelog/foundation/2026-09-27-baseline.yaml'"
```

`1` heißt: nicht betroffen. `0` heißt: neu aufsetzen. Die Abfrage prüft Kennung, Autor **und** Datei,
weil Liquibase ein Changeset an genau diesen drei Werten wiedererkennt: Liegt dasselbe Changeset
später in einer anderen Datei, gilt es für Liquibase als neu, und eine Prüfung nur der Kennung meldete
eine betroffene Installation als nicht betroffen. Ändert ein Stand die Ablage der Baseline, ändert er
diese Abfrage mit. Neu aufsetzen geht so:

1. **Was in der Datenbank liegt, geht verloren** — Konten, Anbieter, Gruppen, Bibliotheken samt
   Konfiguration und Rechten, Chats, Nachweisprotokoll und Rechtehistorie. Ein Dump des alten Stands
   lässt sich nicht in den neuen einspielen; wer Inhalte nachschlagen will, zieht ihn trotzdem vorher.
   Die Konfiguration in `.env.docker` und der Korpus im Dateisystem bleiben.
2. Stapel anhalten und **nur** das Datenbank-Volume verwerfen:

   ```bash
   docker compose down
   docker volume rm <projekt>_opaa-postgres-data
   ```

   `<projekt>` ist der Name des Compose-Projekts, ohne eigene Angabe der Name des Verzeichnisses mit
   der `docker-compose.yml`; `docker volume ls | grep opaa-postgres-data` zeigt den genauen Namen.
   **Nicht** `docker compose down -v`: Das verwirft alle Volumes des Stapels, auch
   `<projekt>_opaa-ollama-data` mit den heruntergeladenen Modellen. Das Volume
   `<projekt>_opaa-backend-tmp` mit Arbeitsdateien des alten Stands kann im selben Zug mit
   `docker volume rm` verworfen werden.
3. Die abgelegten Originale der alten Bibliotheken entfernen — das Verzeichnis `uploads/` bei
   Ablage im Dateisystem, den Inhalt des Buckets bei Ablage im Objektspeicher; beim mitgelieferten
   Objektspeicher genügt `docker volume rm <projekt>_opaa-upload-store-data` (vorher mit
   `docker compose --profile upload-s3 down` auch dessen Container anhalten). Sonst bleiben
   Originale liegen, auf die keine Zeile mehr zeigt (siehe
   [„Verwaiste Originale aufräumen"](#verwaiste-originale-aufräumen)).
4. `docker compose pull && docker compose up -d`. Der erste Start legt das Schema neu an, übernimmt
   den Anbieter aus `OPAA_OIDC_*` und legt das Konto der Systemverwaltung an (siehe
   [„Erststart und Systemverwalter-Konto"](#erststart-und-systemverwalter-konto)).
5. Bibliotheken neu anlegen und indizieren.

## Fehlerbehebung

### Backend gibt leere Antworten oder "Connection refused" zurück

Das Backend bindet standardmäßig an `localhost`, was nur von innerhalb des Containers erreichbar ist. `OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker` setzen.

### POST-Anfragen geben 403 Forbidden zurück

CORS ist wahrscheinlich falsch konfiguriert. Sicherstellen, dass `OPAA_CORS_ALLOWED_ORIGINS` mit der Frontend-URL übereinstimmt (z. B. `http://localhost:3000`). GET-Anfragen können funktionieren, weil sie keinen CORS-Preflight auslösen, während POST-Anfragen mit `Content-Type: application/json` dies tun.

### PostgreSQL: "Passwort-Authentifizierung für Benutzer fehlgeschlagen"

Betrifft die **Datenbank**, nicht ein Benutzerkonto von OPAA: Das PostgreSQL-Volume enthält noch Daten von einer früheren Initialisierung mit anderen Anmeldeinformationen. `docker compose down -v` ausführen, um das Volume zu entfernen und neu zu starten.

### `docker exec` in den Backend-Container schlägt mit "executable file not found" fehl

Das Backend-Laufzeitimage enthält keine Shell (siehe [Backend-Laufzeitimage](#backend-laufzeitimage)).
Statt `docker exec … sh` die dort beschriebenen Wege nutzen: Logs, `/actuator`, `docker cp` oder einen
Werkzeug-Container im selben Netzwerk-Namensraum.

### Jeder Upload endet mit „Interner Serverfehler" und `AccessDeniedException: /app/uploads/…`

Das Host-Verzeichnis hinter `/app/uploads` gehört nicht der Kennung `65532`, unter der das Backend
im Container läuft — meist, weil Docker es beim ersten Start selbst angelegt hat. Behebung mit
`chown` oder einem `user:`-Eintrag, beides unter [„Nicht-root-Betrieb des
Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

### Umgebungsvariablen-Änderungen treten nicht in Kraft

Nach dem Ändern von `.env.docker` `docker compose up -d <service>` (nicht `restart`) verwenden, um den Container mit der neuen Umgebung neu zu erstellen. `restart` verwendet den vorhandenen Container erneut und ignoriert `.env.docker`-Änderungen.

## Stoppen

```bash
docker compose down
```
