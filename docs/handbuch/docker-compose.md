# Docker Compose

Der Compose-Stapel des Repositorys (`docker-compose.yml`) startet OPAA auf einem einzelnen Rechner:
Frontend, Backend und PostgreSQL, dazu über Compose-Profile Keycloak, Ollama, einen Objektspeicher
und einen Mailserver zum Abfangen. Er ist für Entwicklung, Erprobung und kleine Installationen
gedacht; seine Vorgaben sind bequem und **nicht** gehärtet. Was für jeden Betriebsweg gilt — Images,
Bereitschaft, Härtung, Schlüssel, Sicherung —, steht in der [Betriebsübersicht](betrieb.md), die
Bedeutung jeder Variable in der [Konfiguration](konfiguration.md).

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
erste Upload schlägt dann fehl. Warum der Korpus nur Leserecht bekommt, der Weg ohne `sudo` und die
Lage auf einem Netzlaufwerk stehen unter
[„Nicht-root-Betrieb des Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

Voreingestellt sind lokal betriebene Modelle über einen Ollama-Server; in `.env.docker` ist dafür
nichts weiter einzutragen. Für Ollama gibt es zwei Wege, die sich gegenseitig ausschließen:

**Weg A — Ollama als Teil des Stapels (Profil `ollama`).** Die Modelladresse zeigt bereits auf
`http://ollama:11434/v1`. Beim allerersten Start zuerst den Download der Modelle abwarten (mehrere GiB,
siehe [„Lokal betriebenes Ollama im Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack)) —
ein Dokument, das währenddessen hochgeladen wird, endet sonst mit `FAILED`:

```bash
docker compose --profile ollama up ollama-pull   # wartet, bis beide Modelle vorliegen
docker compose --profile ollama up --build       # danach den vollständigen Stapel starten
```

Nur auf diesem Weg in `.env.docker` die Zeile `OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS=8192`
einkommentieren: `ollama-pull` legt `bge-m3` mit dieser Eingabegrenze an. Ohne die Zeile gilt die
Vorgabe 2.048, und OPAA teilt längere Chunks.

Wer nicht warten muss, startet gleich mit `docker compose --profile ollama up --build`; `ollama-pull`
lädt dann im Hintergrund.

**Weg B — ein vorhandener Ollama-Server**, auf dem Host oder in einem anderen Netz. Läuft er auf dem
Host, in `.env.docker` setzen und ohne Profil starten:

```env
OPAA_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
```

```bash
docker compose up --build
```

Auf dem Server müssen die Modelle der Vorgabe vorliegen: `ollama pull bge-m3` und
`ollama pull phi3:mini`, oder die Modelle, die `.env.docker` stattdessen nennt. Die Zeile
`OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS=8192` bleibt auskommentiert, solange `bge-m3` dort nicht per
Modelfile mit dieser Grenze angelegt ist
([Eingabegrenze des Embedding-Modells](modelle.md#eingabegrenze-des-embedding-modells)).

Ohne erreichbaren Server startet der Stapel, aber Indexierung und Fragen schlagen fehl. Danach ist
die Oberfläche unter `http://localhost:3000` erreichbar, die API unter `http://localhost:8081/api`.
Wie der erste Systemverwalter hereinkommt, steht unter
[Authentifizierung](authentifizierung.md#erststart-und-systemverwalter-konto).

## Services

| Service     | Host-Port | Container-Port | Beschreibung                                          |
|-------------|-----------|-----------------|--------------------------------------------------------|
| frontend    | 3000      | 8080            | React-App über nginx bereitgestellt                    |
| backend     | 8081      | 8080            | Spring Boot API                                         |
| postgres    | 5432      | 5432            | PostgreSQL 18 mit pgvector                              |
| keycloak    | 8180      | 8180            | Keycloak (nur `oidc`-/`demo`-Profil)                    |
| ollama      | —         | 11434           | Ollama-Server (nur `ollama`-Profil; kein Host-Port) |
| ollama-pull | —         | —               | einmaliger Init-Schritt, zieht `bge-m3`/`phi3:mini` (nur `ollama`-Profil) |
| upload-store | 8095 (API), 8096 (Konsole unter `/rustfs/console/`) | 9000, 9001 | Objektspeicher der Originalablage (nur `upload-s3`-Profil; an `127.0.0.1` gebunden) |
| upload-store-init | —    | —               | einmaliger Init-Schritt, legt den Bucket der Originalablage an (nur `upload-s3`-Profil) |
| mailpit | 1025 (SMTP), 8025 (Weboberfläche) | 1025, 8025 | Mailserver, der alles annimmt und nichts zustellt (nur `mail`-Profil) |
| backend-migrate | —   | —               | einmaliger Migrationsschritt (nur [Mehrinstanz-Variante](#mehrere-backend-instanzen)) |
| backend-a, backend-b | — | 8080           | zwei Backend-Instanzen statt `backend` (nur Mehrinstanz-Variante) |

Das Backend meldet seinen Zustand über einen Healthcheck auf die
[Bereitschaft](betrieb.md#bereitschaft-und-lebendigkeit); `docker compose ps` zeigt ihn als
`healthy`, und das Frontend startet erst danach. Der Frontend-nginx löst den Namen `backend` zur
Laufzeit auf (`OPAA_BACKEND_RESOLVER`): Ein neu erstelltes Backend erreicht er ohne eigenen Neustart.

Die Profile `demo` (Demo-Instanz „Stadt Rheinfurt" mit eigenen Quellen) und `oidc` (gebündeltes
Keycloak) ergänzen weitere Dienste. Alle Befehle dieses Kapitels sprechen den **Servicenamen** an
(`backend`, `frontend`, `postgres`), nicht einen Containernamen: Wie die Container heißen, hängt vom
Namen des Compose-Projekts ab, ohne eigene Angabe dem Namen des Verzeichnisses mit der
`docker-compose.yml`. `docker compose logs backend` funktioniert in jedem Stapel, `docker logs <name>`
nur in einem.

## Images aus GHCR

Auf einem Zielsystem, auf dem nicht gebaut werden soll, genügt eine `docker-compose.yml`, die
`image:` statt `build:` verwendet; ein Checkout des Repositorys ist nicht nötig. Welcher Tag welchen
Stand bedeutet, steht unter [Images und Versionen](betrieb.md#images-und-versionen).

```yaml
services:
  backend:
    image: ghcr.io/criew/opaa-backend:0.1.0   # oder :main für den Entwicklungsstand
  frontend:
    image: ghcr.io/criew/opaa-frontend:0.1.0
```

Eine eigene Compose-Datei bildet den Host-Port des Frontends auf den Containerport `8080` ab, nicht
auf `80`.

## Konfiguration mit `.env.docker`

Die Dienste lesen ihre Umgebung aus `.env.docker` (`env_file`); alle Optionen mit Beschreibung stehen
in `.env.docker.example`, die Bedeutung jeder Variable in der [Konfiguration](konfiguration.md).

- **Zwei Vorlagen, zwei Zwecke.** `.env.example` ist auf `./gradlew bootRun` auf dem Host
  zugeschnitten (Datenbank auf `localhost`, CORS auf den Vite-Dev-Server `:5173`).
  `.env.docker.example` ist die Vorlage für den Stapel: `SPRING_PROFILES_ACTIVE=docker,dev`, die
  Datenbankadresse aus dem Profil `docker` (Hostname `postgres`), CORS auf den Frontend-Port `:3000`.
  Wer beide unverändert nebeneinander nutzt, bekommt in beiden Fällen einen startfähigen Stand.
- **`.env` und `.env.docker` sind verschieden.** Docker Compose liest eine `.env` im
  Projektverzeichnis von selbst, aber nur für die Platzhalter in `docker-compose.yml` selbst — Ports,
  Bind-Mounts, die Auswahl der `env_file`, die Zugangsdaten mitgelieferter Dienste. Solche Werte
  wirken nur aus der Shell-Umgebung oder einer `.env`, nie aus `.env.docker`; die
  [Konfiguration](konfiguration.md#alle-umgebungsvariablen) kennzeichnet sie. Wer eine `.env` für die
  lokale Entwicklung hat, achtet darauf, dass Ports und Datenbankzugang nicht kollidieren.
- **Änderungen wirken erst mit neuem Container:** `docker compose up -d <service>`, nicht `restart` —
  `restart` verwendet den vorhandenen Container samt alter Umgebung erneut.

### Docker-spezifische Variablen

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker,oidc` (Betrieb) oder `docker,dev` (Entwicklung) | Aktiviert die Docker-spezifische Konfiguration (Datenbankadresse, Modelladresse) und den Betriebsmodus; ohne `oidc` oder `dev` startet das Backend nicht |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Das Backend muss an alle Schnittstellen binden, damit der nginx des Frontend-Containers es erreicht. `0.0.0.0` bindet IPv4 und, wo vorhanden, IPv6; `::` verhindert den Start in einem Container ohne IPv6 |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | muss mit dem Host-Port des Frontends übereinstimmen |
| `OPAA_DB_USERNAME`, `OPAA_DB_PASSWORD` | `opaa`, `opaa` | müssen zwischen Backend und `postgres` übereinstimmen |

### Minimale `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker,dev
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
```

Das voreingestellte Embedding-Modell `bge-m3` bettet mit derselben Dimension ein, die
`OPAA_PGVECTOR_DIMENSIONS` vorgibt. Für einen anderen Modellanbieter kommen `OPAA_OPENAI_BASE_URL`
und `OPAA_OPENAI_API_KEY` hinzu, und `OPAA_PGVECTOR_DIMENSIONS` richtet sich nach dessen
Embedding-Modell ([Modelle](modelle.md#welches-embedding-modell)); passt sie nicht, bricht die erste
Einbettung mit einem Dimensionsfehler ab.

## Dokumente

Dokumente für eine Dateisystem-Bibliothek liegen im Verzeichnis `./documents` (oder dem Pfad aus
`OPAA_INDEXING_DOCUMENT_PATH_HOST`). Es wird im Backend-Container unter `/app/documents` eingehängt.

## Datenbank im Stapel

Die Daten von PostgreSQL liegen im benannten Volume `opaa-postgres-data` und überleben
`docker compose down` und `up`. Zurückgesetzt wird die Datenbank mit:

```bash
docker compose down -v
```

- **Konto vor dem ersten Start festlegen.** PostgreSQL legt das Konto aus `OPAA_DB_USERNAME` und
  `OPAA_DB_PASSWORD` nur beim ersten Start an; eine spätere Änderung wird ignoriert, bis das Volume
  verworfen ist.
- **`down -v` verwirft alle Volumes des Stapels**, mit dem Profil `ollama` auch `opaa-ollama-data`
  mit den heruntergeladenen Modellen (rund 3,3 GiB, die `ollama-pull` danach neu lädt) und mit
  `upload-s3` den Objektspeicher samt allen Originalen. Wer nur die Datenbank verwerfen will, nimmt
  `docker volume rm <projekt>_opaa-postgres-data` bei angehaltenem Stapel.
- **Eine eigene PostgreSQL** statt des Dienstes `postgres` braucht pgvector und einige Vorbereitung
  ([Datenbank](datenbank.md)).

## Lokal betriebenes Ollama im Compose-Stack

Das Profil `ollama` startet einen Ollama-Server im Stapel. Es ist nicht voreingestellt: Image und
Modelle sind groß, und wer einen Ollama-Server schon betreibt, braucht keinen zweiten.

```bash
docker compose --profile ollama up --build
```

- **`ollama`** ist der Server, mit dem Volume `opaa-ollama-data` für die Modelle. Er veröffentlicht
  keinen Host-Port; Backend und Init-Schritt erreichen ihn im Compose-Netz unter dem Namen `ollama`,
  auf den die Modelladresse im Profil `docker` bereits zeigt. Der auskommentierte `ports`-Eintrag
  bindet, falls aktiviert, nur an `127.0.0.1`.
- **`ollama-pull`** zieht `bge-m3` (Einbettung) und `phi3:mini` (Chat) und beendet sich. Beide sind
  nötig: `phi3:mini` wird beim ersten Start zum aktiven Chat-Modell
  ([Modelle](modelle.md#llm-anbieter)), ohne es scheitert die erste Frage. `bge-m3` legt der Schritt
  danach unter demselben Namen mit einer Eingabegrenze von 8.192 Tokens neu an, damit jeder Chunk
  ungekürzt eingebettet wird ([Eingabegrenze](modelle.md#eingabegrenze-des-embedding-modells)).
  Dazu gehört die einkommentierte Zeile `OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS=8192` aus Weg A.
  Wer stattdessen `nomic-embed-text` nutzt, zieht es mit
  `docker compose --profile ollama exec ollama ollama pull nomic-embed-text`, setzt Modell und
  Dimension in `.env.docker` und lässt die Zeile auskommentiert. Bei einem erneuten Start lädt der Schritt nichts doppelt herunter und legt das Modell erneut an.

**Platz und Zeit.** Die Modelle laden beim ersten Start rund 3,3 GiB herunter (`phi3:mini` rund
2,2 GiB, `bge-m3` rund 1,1 GiB, komprimiert), das Image selbst ist mit rund 3,4 GB (amd64) noch
größer. Beim Einbetten braucht `bge-m3` mehrere Gigabyte Arbeitsspeicher
([Welches Embedding-Modell](modelle.md#welches-embedding-modell)). Den Fortschritt zeigt `docker compose logs -f ollama-pull`. Der Stapel ist nutzbar,
sobald `ollama` gesund ist; bis beide Modelle vorliegen, können Indexierung und Fragen scheitern.

**Fehlgeschlagener Download.** Scheitert `ollama-pull` (Registry nicht erreichbar, Platte voll),
laufen Backend und Frontend unauffällig weiter, nur Indexierung und Chat schlagen fehl. Die Ursache
steht in `docker compose logs ollama-pull` — der erste Blick bei einem unerklärten `FAILED` direkt
nach dem Start.

**Ohne Internet.** `ollama pull` prüft bei jedem Aufruf gegen die Registry im Internet und scheitert
ohne Zugang, auch wenn beide Modelle schon im Volume liegen. In einem abgeschotteten Netz entweder
das `command` in `docker-compose.yml` um einen vorangestellten `ollama list | grep -q ... ||` ergänzen
oder `ollama-pull` beim erneuten Start weglassen
(`docker compose --profile ollama up ollama backend frontend postgres`).

**GPU.** Ohne Ergänzung nutzt `ollama` nur die CPU. Eine NVIDIA-GPU mit installiertem Container-Toolkit
reicht ein eigener `deploy.resources.reservations.devices`-Eintrag am Dienst `ollama` durch.

**Ein vorhandener Server statt des Profils** ist gleichwertig (Weg B im [Schnellstart](#schnellstart));
`docker-compose.yml` setzt den nötigen `extra_hosts`-Eintrag für `host.docker.internal` bereits. Er
eignet sich, wenn ihn mehrere Installationen nutzen sollen.

## Der mitgelieferte Objektspeicher im Compose-Stapel

Das Profil `upload-s3` bringt einen Objektspeicher für die [Originalablage](originalablage.md) mit.
Ohne das Profil existieren die beiden Dienste nicht.

```bash
docker compose --profile upload-s3 up -d upload-store upload-store-init
```

- `upload-store` hält die Originale im eigenen Volume `opaa-upload-store-data`. `docker compose down -v`
  löscht es samt allen Originalen; für den regulären Stopp genügt `docker compose down`.
- `upload-store-init` legt einmal den Bucket an und beendet sich; den Inhalt schreibt allein die
  Anwendung.
- Das Backend hängt von keinem der beiden ab: Ein kurz nicht erreichbarer Speicher hält den Start
  nicht auf.

In `.env.docker` gehören dann `OPAA_UPLOAD_STORE`, `OPAA_UPLOAD_S3_ENDPOINT`, `OPAA_UPLOAD_S3_BUCKET`,
`OPAA_UPLOAD_S3_ACCESS_KEY` und `OPAA_UPLOAD_S3_SECRET_KEY`; `.env.docker.example` trägt sie
auskommentiert mit den Werten, die der Dienst voreingestellt hat. Die Zugangsdaten des Dienstes
**selbst** (`OPAA_UPLOAD_STORE_ROOT_USER`, `OPAA_UPLOAD_STORE_ROOT_PASSWORD`,
`OPAA_UPLOAD_STORE_BUCKET`) liest Compose nur aus der Shell-Umgebung oder einer `.env`. Wer sie
ändert, ändert sie auf beiden Seiten, sonst weist der Speicher jeden Zugriff zurück. Der Endpunkt
zeigt auf den Servicenamen und passiert die Zielprüfung der Originalablage ohne eigenen Eintrag.

Dieser Dienst ist nicht der Objektspeicher des Profils `demo`: Jener füllt einen Korpus-Bucket, aus
dem eine Bibliothek als **Quelle** liest, wird bei jedem Start neu befüllt und hat kein Volume. Beide
Profile laufen nebeneinander.

## Mailpit im lokalen Stapel

Für Erprobung und Entwicklung liegt ein Mailserver bei, der alles annimmt und nichts zustellt:

```bash
docker compose --profile mail up -d mailpit
```

Die abgefangenen Nachrichten liegen unter `http://localhost:8025`. Unter „Administration → E-Mail →
SMTP-Zugang" einzutragen: Server `mailpit`, Port `1025`, Verschlüsselung `NONE`, keine Zugangsdaten
([E-Mail-Versand](e-mail.md)).

## Mehrere Backend-Instanzen

`docker-compose.multi.yml` ergänzt den Stapel um zwei Backend-Instanzen auf demselben Rechner: Fällt
eine aus, antwortet die andere, und ein Update geht ohne Ausfall der Instanzen
([Eine oder mehrere Backend-Instanzen](betrieb.md#eine-oder-mehrere-backend-instanzen)). Jeder
Befehl für diesen Stapel nennt beide Dateien:

```bash
docker compose -f docker-compose.yml -f docker-compose.multi.yml up -d
```

Statt `backend` laufen dann `backend-migrate`, das die Datenbank migriert und endet, und die
Instanzen `backend-a` und `backend-b`. Sie starten erst nach dem Migrationsschritt, migrieren selbst
nicht und haben je ein eigenes `/tmp`, aber keinen Host-Port. Beide tragen im Compose-Netz den Namen
`backend`; der Frontend-nginx verteilt die Anfragen auf beide und weicht auf die andere aus, wenn eine
keine Verbindung annimmt. `docker compose --scale` ist nicht der Weg zu einer weiteren Instanz: Die
skalierten Container teilten sich ein `/tmp`.

- **Originale:** Empfohlen ist der Objektspeicher (`OPAA_UPLOAD_STORE=s3`, etwa mit dem Profil
  `upload-s3`). Das gemeinsame Verzeichnis `uploads/` genügt, weil alle Instanzen auf diesem einen
  Rechner laufen; Instanzen auf mehreren Rechnern brauchen den Objektspeicher.
- **Konfiguration:** Beide Instanzen lesen dieselbe `.env.docker` und haben damit denselben
  [Konfigurationsabdruck](betrieb.md#instanzregister).
- **Grenzen je Instanz:** Die Gesamtgrenzen der Rate-Limits (`…_GLOBAL_MAX_REQUESTS`) zählen beide
  Instanzen gemeinsam. Die übrigen Rate-Limits und die Thread-Pools gelten je Instanz; ein Wert, der
  für die ganze Installation gemeint ist, steht in `.env.docker` durch die Zahl der Instanzen geteilt,
  etwa `OPAA_INDEXING_EMBEDDING_CONCURRENCY=3` für zusammen sechs gleichzeitige Einbettungen (siehe
  [„Grenzen und Anzeigen mit mehreren Instanzen“](betrieb.md#grenzen-und-anzeigen-mit-mehreren-instanzen)).
- **Datenbankverbindungen:** Die Grenze nennt
  [Verbindungen bei mehreren Instanzen](datenbank.md#verbindungen-bei-mehreren-instanzen).
- **Vertraute Proxys:** Die feste Adresse des Frontend-Containers in
  `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` genügt, solange genau ein Frontend-Container vor den Instanzen
  steht. Ein zweiter Frontend-Container braucht eine eigene feste Adresse im Compose-Netz und einen
  eigenen Eintrag; mit `docker compose --scale frontend` bekämen die Container Adressen, die sich nicht
  einzeln vertrauen lassen. Ein weiterer Proxy davor kommt hinzu wie unter
  [Vertraute Proxys](betrieb.md#vertraute-proxys-und-client-adresse) beschrieben.

### Update ohne Ausfall

Compose ersetzt Container nicht nacheinander von selbst. Die Reihenfolge, eine Instanz nach der
anderen, jeweils bis sie `healthy` ist:

```bash
F="-f docker-compose.yml -f docker-compose.multi.yml"
docker compose $F pull                              # oder build
docker compose $F run --rm backend-migrate          # Migration der neuen Version
docker compose $F up -d --no-deps --wait backend-a  # wartet, bis backend-a healthy ist
sleep 15
docker compose $F up -d --no-deps --wait backend-b
docker compose $F up -d --no-deps --wait frontend
```

1. **Migration zuerst.** Sie erweitert das Schema nur, die laufenden Instanzen der alten Version
   arbeiten weiter. Endet `run` nicht mit Exit-Code 0, hier aufhören: Die alten Instanzen laufen
   unverändert.
2. **Dann `backend-a`.** Während Compose den Container ersetzt, beantwortet `backend-b` alle Anfragen;
   laufende Anfragen von `backend-a` enden geordnet ([Sanftes Herunterfahren](betrieb.md#sanftes-herunterfahren)).
3. **Dann `backend-b`**, erst wenn `backend-a` wieder `healthy` ist und eine kurze Pause vergangen
   ist; `--wait` wartet auf das eine, `sleep 15` sorgt für das andere. Der Frontend-nginx nimmt eine
   Instanz, die beim Start kurz keine Verbindung annahm, erst danach sicher wieder in die Verteilung;
   ohne die Pause können für einige Sekunden Anfragen mit `502` scheitern.
4. **Das Frontend zuletzt.** Es ist ein einzelner Container. Während Compose ihn ersetzt, lehnt der
   Port etwa eine Sekunde lang Verbindungen ab; keine Anfrage wird mit einem Fehler beantwortet, und
   die Oberfläche lädt danach weiter.

Während des Updates laufen kurz alte und neue Version nebeneinander gegen dieselbe Datenbank. Mails,
Läufe und Uploads gehen dabei nicht verloren ([Sanftes Herunterfahren](betrieb.md#sanftes-herunterfahren)).

Nennen die Notizen eines Release eine Versionsgrenze ohne Rolling Update, oder ändert sich ein Wert
des Konfigurationsabdrucks, werden zuerst beide Instanzen angehalten
(`docker compose $F stop backend-a backend-b`), dann folgen Migration und Start wie oben.

## Nicht-root-Betrieb des Backend-Containers

Das Backend läuft als Benutzer und Gruppe `65532`. Bei einem Bind-Mount gilt der Eigentümer des
**Host**-Verzeichnisses; Docker ändert daran beim Einhängen nichts. Daraus folgen drei Anforderungen:

1. **`/app/uploads` muss für `65532` beschreibbar sein**, solange die Originale im Dateisystem liegen
   (`OPAA_UPLOAD_STORE` nicht auf `s3`) — am einfachsten, indem das Host-Verzeichnis dieser Kennung
   gehört.
2. **Ein eigenes Temp-Verzeichnis muss für `65532` beschreibbar sein.** Ohne Angabe zeigen
   `OPAA_UPLOAD_S3_TEMP_DIRECTORY` und `OPAA_INDEXING_S3_TEMP_DIRECTORY` auf `/tmp`; wer sie auf ein
   großes eingehängtes Volume legt, übergibt es. Sonst scheitert jeder Upload beim Anlegen der
   Arbeitsdatei, auch mit `OPAA_UPLOAD_STORE=s3`.
3. **Jedes weitere eingehängte Verzeichnis muss für `65532` les- und betretbar sein**: `/app/documents`
   und jedes über `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` freigegebene Verzeichnis. Eine eng gesetzte
   Freigabe (`root:root 0700`, ein CIFS-Mount mit `uid=0,file_mode=0600`) ist es nicht
   ([Konnektor Dateisystem](konnektoren/filesystem.md)).

Der häufigste Fall ist der erste: Legt Docker das fehlende Verzeichnis selbst an, gehört es `root`,
der Start meldet `UP`, und erst der erste Upload endet mit `500` und dieser Meldung:

```text
java.io.UncheckedIOException: Datei konnte nicht gespeichert werden
Caused by: java.nio.file.AccessDeniedException: /app/uploads/<Organisations-ID>
```

**Neue Installation:** Schritt 2 im [Schnellstart](#schnellstart). **Bestehende Installation:**
dieselben Zeilen ohne `mkdir`; sie ändern Rechte und Eigentümer, keine Inhalte.

```bash
docker compose down
sudo chown -R 65532:65532 uploads
chmod -R o+rX documents
docker compose pull && docker compose up -d
```

**Warum `documents` nicht übereignet wird.** Den Korpus befüllt typischerweise jemand anderes — ein
Fachverfahren, ein Sync-Dienst, eine Freigabe. Ein `chown` nähme diesem Schreiber die Rechte. Gebraucht
wird Lese- und Betretungsrecht für „andere", bei den üblichen `0755` bereits vorhanden; `chmod -R o+rX`
ist nur für einen enger gesetzten Korpus nötig. Sonst helfen eine gemeinsame Gruppe oder der
`user:`-Weg unten. Dasselbe gilt für jedes freigegebene Verzeichnis einer Dateisystem-Bibliothek.

**Ohne `sudo`, mit eigener Kennung.** Wer die Verzeichnisse nicht abgeben kann — etwa weil sie einem
Dienstkonto gehören sollen —, lässt den Container unter dieser Kennung laufen; die Zeile steht in der
`docker-compose.yml` auskommentiert:

```yaml
services:
  backend:
    user: "1000:1000" # die eigene uid:gid, `id -u`/`id -g`
```

Das ist der Ausweg, nicht der empfohlene Weg: Die Kennung ist bei jedem Umzug und in jeder
abgeleiteten Compose-Datei mitzupflegen. `user: "0:0"` erfüllt denselben Zweck und gibt den
Sicherheitsgewinn auf. Bei einem **CIFS/SMB-Mount** ist dieser Weg oder die Mount-Option
`uid=65532,gid=65532` der einzige, weil `chown` dort ins Leere läuft.

**Benannte Volumes brauchen nichts davon — und vertragen sich nicht mit `user:`.** Ein leeres
benanntes Volume übernimmt beim ersten Einhängen die Eigentümer aus dem Image, also `65532`; genau
deshalb ist es für einen Container mit `user: "1000:1000"` nicht beschreibbar. Wer `user:` setzt,
bleibt beim Bind-Mount. Mit `OPAA_UPLOAD_STORE=s3` entfällt Anforderung 1.

**Beschreibbare Pfade im Stapel.** Das Backend läuft mit `read_only: true` und `no-new-privileges`;
`/tmp` ist das benannte Volume `opaa-backend-tmp` (warum, siehe
[Nur lesbares Dateisystem](betrieb.md#nur-lesbares-dateisystem)). Es überlebt `docker compose down`.
Was ein harter Abbruch, ein Heap-Dump oder ein JFR-Mitschnitt dort hinterlässt, wird bei angehaltenem
Backend entfernt:

```bash
docker compose stop backend && docker compose rm -f backend
docker volume rm <projekt>_opaa-backend-tmp
docker compose up -d backend
```

## Eigene interne CA ergänzen

`-Djavax.net.ssl.trustStore=…` **ersetzt** den mitgelieferten Truststore, statt ihn zu ergänzen (siehe
[Backend-Laufzeitimage](betrieb.md#backend-laufzeitimage)). Richtig ist, den mitgelieferten Store zu
kopieren, zu ergänzen und an seinen Platz zurückzumounten:

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

Die Kopie muss für `65532` **lesbar** sein; bei einer restriktiven `umask` (etwa `077`) ist sie es
nicht, und der Start bricht mit einer Meldung über den Truststore ab — `chmod 644 ./cacerts` genügt.
Nach einem Image-Update mit neuen öffentlichen Wurzelzertifikaten ist der Schritt zu wiederholen:
Die gemountete Kopie überdeckt den Stand des Images.

## Diagnose im Container

Was die Actuator-Pfade zeigen, steht unter [Diagnose ohne Shell](betrieb.md#diagnose-ohne-shell).
Für den Container selbst:

```bash
docker compose logs -f backend                       # Logs
docker cp <container>:/app/uploads ./uploads-kopie   # Dateien aus dem Container holen
docker run --rm -it --network container:<container> nicolaka/netshoot   # Netzwerkdiagnose im selben Netz
```

`docker cp … /app/uploads` gilt nur für die Ablage im Dateisystem; mit `OPAA_UPLOAD_STORE=s3` ist das
Verzeichnis leer oder trägt einen Altbestand, und der Befehl kopiert genau das, ohne Hinweis
([Originalablage](originalablage.md#was-im-objektspeicher-liegt)). `docker cp` läuft über den
Docker-Daemon, nicht unter der Kennung des Containers.

Thread- und Heap-Dump ohne Shell:

```bash
docker exec <container> /opt/java/openjdk/bin/jcmd 1 Thread.print
docker exec <container> /opt/java/openjdk/bin/jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp <container>:/tmp/heap.hprof ./heap.hprof
docker run --rm -v <projekt>_opaa-backend-tmp:/t busybox rm -f /t/heap.hprof   # Dump im Container entfernen
docker kill -s QUIT <container>   # Alternative: Thread-Dump ins Log, braucht nichts im Image
```

Der Dump liegt mit absolutem Pfad unter `/tmp`, weil das übrige Dateisystem nur lesbar ist; er
enthält Geheimnisse und Inhalte und bleibt im Volume, bis er entfernt wird — deshalb die vierte Zeile.
`docker exec` auf `jcmd` läuft ohne `--user` unter derselben Kennung wie der Prozess und darf sich
deshalb an ihn hängen. `docker kill -s QUIT` beendet den Container **nicht**: Die JVM schreibt
daraufhin einen Thread-Dump in `docker compose logs`. Für alles Weitere lässt sich ein
Werkzeug-Container in denselben Netzwerk- oder PID-Namensraum hängen (`--network container:…`,
`--pid container:…`).

## Härtung des Compose-Stapels

Die Vorgaben des Stapels (`docker-compose.yml`, `keycloak/realm-export.json`) sind für die lokale
Entwicklung gewählt: ohne Ersteinrichtung nutzbar, mit funktionierendem Keycloak-Realm ab dem ersten
Start. Für eine erreichbare Installation ist jede davon ein Problem. Was allgemein gilt, steht unter
[Härtung](betrieb.md#härtung-für-erreichbare-deployments), was den mitgelieferten Keycloak betrifft,
unter [Authentifizierung](authentifizierung.md#härtung-des-mitgelieferten-keycloak). Für den Stapel
selbst:

| Vorgabe | Risiko bei erreichbarem Betrieb | Gegenmaßnahme |
|---|---|---|
| `postgres` und `backend` binden an `127.0.0.1`, `frontend` und `keycloak` an allen Schnittstellen | Loopback schützt vor dem Netz, nicht vor anderen Prozessen und Konten desselben Hosts; über den Backend-Port ließe sich `X-Forwarded-For` am Frontend vorbei setzen. Die beiden anderen Ports sind ohne Firewall aus dem Netz erreichbar, bei `keycloak` samt Admin-Konsole | Hinter einem Reverse-Proxy alle Ports an `127.0.0.1:` binden — für `keycloak` **zwingend**, für `frontend` empfohlen. Die `ports:`-Zuordnung von `postgres` ganz entfernen; das Backend erreicht die Datenbank im Compose-Netz. Für die lokale Entwicklung bleiben die Bindungen sinnvoll |
| Zugangsdaten des mitgelieferten Objektspeichers (`opaa-uploads`/`OpaaUploads!2026`, Profil `upload-s3`) | Die Originale aller hochgeladenen Dokumente sind mit Zugangsdaten geschützt, die im Repository stehen | **Zwingend** außerhalb einer Erprobung: eigene Werte über `OPAA_UPLOAD_STORE_ROOT_USER`/`OPAA_UPLOAD_STORE_ROOT_PASSWORD` in `.env` oder der Shell-Umgebung, dieselben als `OPAA_UPLOAD_S3_ACCESS_KEY`/`OPAA_UPLOAD_S3_SECRET_KEY` in `.env.docker`. Im erreichbaren Betrieb ist ein hauseigener Objektspeicher mit Verschlüsselung ruhender Daten und Versionierung der Regelfall |
| `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` leer | Alle Clients teilen hinter dem Frontend-nginx einen Zähler | die feste Adresse des Frontend-Containers als `/32` eintragen, wie in `.env.docker.example` ([Vertraute Proxys](betrieb.md#vertraute-proxys-und-client-adresse)) |

Eine eigene `docker-compose.prod.yml` gibt es nicht. Mehrere Maßnahmen verlangen eine Änderung am
Realm-Export, einen Schritt nach der Ersteinrichtung oder eine externe Datenbank für Keycloak — eine
zweite Compose-Datei würde nur die Port-Bindungen automatisieren.

Ein gehärtetes Beispiel ist die öffentliche Demo-Instanz des Projekts: alle Ports an `127.0.0.1`, ein
TLS-terminierender nginx auf dem Host, Keycloak unter dem Unterpfad `/idp`, der Entwicklungsnutzer
`testuser` deaktiviert, das Passwort des Keycloak-Administrators nach jedem Befüllen rotiert, der
Client `opaa-seed` nur für die Dauer des Befüllens aktiviert und der Verzeichniszugang mit eigenem
Geheimnis.

## Sicherung und Wiederherstellung

Was zu sichern ist und in welcher Reihenfolge, steht unter
[Sicherung und Wiederherstellung](betrieb.md#sicherung-und-wiederherstellung). Im Stapel, bei
angehaltenem Backend (`docker compose stop backend`):

```bash
# Datenbank sichern
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > opaa-db.dump

# Originale sichern (Ablage im Dateisystem)
tar -C uploads -cf opaa-uploads.tar .
```

Dazu `.env.docker`, eine `.env` und eine eigene `docker-compose.yml`. Mit dem mitgelieferten
Objektspeicher sichert man den Bucket über dessen Werkzeuge, etwa `mc mirror`
([Originalablage](originalablage.md#eine-laufende-installation-auf-den-objektspeicher-umstellen)).

Zurückspielen in umgekehrter Reihenfolge, das Backend bleibt bis zum Schluss angehalten:

```bash
# Originale zurückspielen und das Eigentum zurückgeben
sudo tar -C uploads -xf opaa-uploads.tar
sudo chown -R 65532:65532 uploads

# Datenbank leeren, neu anlegen, Sicherung einspielen
docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" &&
  createdb -U "$POSTGRES_USER" "$POSTGRES_DB" &&
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' < opaa-db.dump
```

**Auf einem frischen Datenbank-Volume**, etwa auf einem neuen Host, fehlt die Rolle
`opaa_audit_owner`: Sie gehört zur PostgreSQL-Instanz, nicht zur Datenbank, steht deshalb nicht im
Dump und entsteht sonst nur beim ersten Start des Backends. Ohne sie bricht `pg_restore` mit
`role "opaa_audit_owner" does not exist` ab ([Sicherung und Wiederherstellung](betrieb.md#sicherung-und-wiederherstellung)).
Vor dem Einspielen deshalb einmal anlegen, mit angehaltenem Backend:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE ROLE opaa_audit_owner NOLOGIN" -c "GRANT opaa_audit_owner TO \"$POSTGRES_USER\""'
```

Danach `OPAA_AUTH_JWT_SECRET` in `.env.docker` ersetzen und erst dann `docker compose up -d backend`
([Nacharbeit nach einer Rücksicherung](authentifizierung.md#nacharbeit-nach-einer-rücksicherung-der-datenbank)).

## Aktualisierung auf einen neuen Stand

Im Verzeichnis mit der `docker-compose.yml`:

```bash
docker compose pull          # neue Images holen
docker compose up -d         # Container mit den neuen Images neu erstellen
docker image prune -f        # optional: verdrängte Images aufräumen
```

`up -d` ersetzt nur die Container, deren Image sich geändert hat. Mit der Mehrinstanz-Variante gilt
die Reihenfolge unter [Update ohne Ausfall](#update-ohne-ausfall). `docker compose restart` genügt
**nicht**: Es startet den vorhandenen Container samt altem Image. Zustand danach:

```bash
docker compose ps
docker compose logs -f backend
```

Der Stand `main` bekommt bei jedem Push und wöchentlich neue Images; ein `pull` bringt deshalb auch
ohne neuen Commit einen neuen Stand mit Sicherheitsupdates ([Images und Versionen](betrieb.md#images-und-versionen)).
Ein Skript, das genau diese Schritte regelmäßig ausführt, ist eine naheliegende Automatisierung; so
läuft die öffentliche Demo-Instanz, täglich per Cron. Ein solches Skript trennt einen Schalter, der
zusätzlich die Volumes verwirft, klar vom regulären Lauf: Das ist kein Update, sondern ein
Neuaufsetzen mit vollständiger Neuindizierung.

Was ein Update mit dem Index macht, steht unter
[Aktualisierung](betrieb.md#was-ein-update-mit-dem-index-macht).

### Vorbereitungsschritte für Bestandsinstallationen

Welche Schritte ein Release verlangt, nennen seine Notizen im Abschnitt „Vorbereitung für
Bestandsinstallationen"; für Einzelheiten verweisen sie hierher. Eine `.env.docker` oder eine eigene
Compose-Datei aus einem älteren Stand braucht diese Punkte, alle **vor** dem `docker compose up -d`.
Ohne Punkt 1 startet das Backend nicht, ohne Punkt 2 entsteht kein Systemverwalterkonto, ohne
Punkt 5 scheitert der erste Upload, ohne Punkt 6 ist die Oberfläche nicht erreichbar:

1. **`OPAA_AUTH_JWT_SECRET` setzen — Pflicht.** Im Betriebsmodus `oidc` bricht der Start ohne es ab,
   ebenso bei weniger als 32 Zeichen oder einem Platzhalter. Erzeugen mit `openssl rand -base64 48`
   ([Schlüssel und Geheimnisse](betrieb.md#schlüssel-und-geheimnisse)).
2. **`OPAA_INITIAL_ADMIN_EMAIL` auf ein zustellbares Postfach setzen**, am besten ein
   Funktionspostfach der IT. Der frühere Vorgabewert `admin@opaa.local` wird abgelehnt; OPAA legt dann
   kein Systemverwalterkonto an und sagt das im Log
   ([Erststart](authentifizierung.md#erststart-und-systemverwalter-konto)).
3. **`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` setzen**, sobald ein Proxy vor dem Backend steht — und der
   nginx des Frontend-Containers ist einer. Eingetragen wird seine feste Adresse als `/32`; ohne sie
   gilt die Anmeldegrenze für das ganze Haus ([Vertraute Proxys](betrieb.md#vertraute-proxys-und-client-adresse)).
4. **`OPAA_PUBLIC_BASE_URL` setzen** — empfohlen, Pflicht für Einladungen per Mail und für Zugänge
   mit Anmeldeart OAuth: Sie steht in jedem Link einer Mail und bildet die Rücksprungadresse
   `{OPAA_PUBLIC_BASE_URL}/connections/callback` ([E-Mail-Versand](e-mail.md)).
5. **`uploads/` der Kennung `65532` übergeben**, solange die Originale im Dateisystem liegen:
   `sudo chown -R 65532:65532 uploads`. Ebenfalls prüfen: ob `documents/`, jedes über
   `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` freigegebene Verzeichnis und ein eigenes Temp-Verzeichnis für
   `65532` zugänglich sind; ein eigenes Temp-Verzeichnis liegt auf einem eingehängten Volume oder unter
   `/tmp`. Ein Verstoß zeigt sich erst beim ersten Upload bzw. Lauf
   ([Nicht-root-Betrieb](#nicht-root-betrieb-des-backend-containers)).
6. **Containerport des Frontends auf `8080`** in jeder eigenen Compose-Datei: die Port-Abbildung des
   Dienstes `frontend` (etwa `"127.0.0.1:3000:8080"`) und ein eigener Proxy im Docker-Netz
   (`frontend:8080`). Bildet sie noch auf `80` ab, antwortet der Host-Port nicht, und ein
   vorgeschalteter Proxy meldet `502`. Die `docker-compose.yml` des Repositorys tut das bereits.

Bestehende Konten, Rollen, Rechte und der Index bleiben, wie sie sind; ein Identitätsanbieter läuft
unverändert weiter, und die lokale Benutzerverwaltung bleibt aus, bis sie jemand einschaltet. Die
Ausnahme: `OPAA_INITIAL_ADMIN_EMAIL` macht kein Konto eines Identitätsanbieters mehr zum
Systemverwalter; bereits vergebene Rollen bleiben.

### Neu aufsetzen statt aktualisieren

Fassen die Notizen eines Release die Schemahistorie zu einer neuen Baseline zusammen, lässt sich eine
ältere Datenbank nicht aktualisieren, sondern nur neu aufsetzen. So ist es beim Stand von Ende
September 2026: Die Baseline liegt seitdem je Modul (`db/changelog/foundation/` bis
`db/changelog/external/`), und eine Datenbank von vorher startet nicht. Ob eine Installation betroffen
ist, zeigt vor dem Update:

```bash
docker compose exec postgres psql -U opaa -d opaa -tAc \
  "SELECT count(*) FROM databasechangelog WHERE id = 'foundation-2026-09-27-baseline'
     AND author = 'opaa' AND filename = 'db/changelog/foundation/2026-09-27-baseline.yaml'"
```

`1` heißt: nicht betroffen. `0` heißt: neu aufsetzen. Die Abfrage prüft Kennung, Autor **und** Datei,
weil die Migration einen Schritt an genau diesen drei Werten wiedererkennt. Neu aufsetzen:

1. **Was in der Datenbank liegt, geht verloren** — Konten, Anbieter, Gruppen, Bibliotheken samt
   Konfiguration und Rechten, Chats, Nachweisprotokoll und Rechtehistorie. Ein Dump des alten Stands
   lässt sich nicht in den neuen einspielen; wer Inhalte nachschlagen will, zieht ihn trotzdem vorher.
   `.env.docker` und der Korpus bleiben.
2. Stapel anhalten und **nur** das Datenbank-Volume verwerfen, nicht alle mit `down -v`:

   ```bash
   docker compose down
   docker volume rm <projekt>_opaa-postgres-data
   ```

   `docker volume ls | grep opaa-postgres-data` zeigt den genauen Namen. Das Volume
   `<projekt>_opaa-backend-tmp` kann im selben Zug verworfen werden.
3. Die Originale der alten Bibliotheken entfernen — `uploads/` bei Ablage im Dateisystem, den Inhalt
   des Buckets beim Objektspeicher; beim mitgelieferten genügt nach
   `docker compose --profile upload-s3 down` ein `docker volume rm <projekt>_opaa-upload-store-data`.
   Sonst bleiben Originale liegen, auf die nichts mehr zeigt.
4. `docker compose pull && docker compose up -d`. Der erste Start legt das Schema an, übernimmt den
   Anbieter aus `OPAA_OIDC_*` und legt das Konto der Systemverwaltung an.
5. Bibliotheken neu anlegen und indizieren.

Variablen, die seit älteren Ständen entfallen sind, nennt die
[Konfiguration](konfiguration.md#entfallene-und-veraltete-variablen); eine übernommene `.env.docker`
wird daraufhin geprüft.

## Fehlerbehebung

### Backend gibt leere Antworten oder „Connection refused" zurück

Das Backend bindet ohne Angabe an `localhost`, was nur innerhalb des Containers erreichbar ist.
`OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker` setzen.

### POST-Anfragen geben 403 Forbidden zurück

CORS passt nicht: `OPAA_CORS_ALLOWED_ORIGINS` muss die Adresse des Frontends nennen (etwa
`http://localhost:3000`). GET-Anfragen funktionieren trotzdem, weil sie keinen CORS-Preflight auslösen.

### PostgreSQL: „Passwort-Authentifizierung für Benutzer fehlgeschlagen"

Betrifft die **Datenbank**, kein Konto von OPAA: Das Volume stammt aus einer früheren Initialisierung
mit anderen Zugangsdaten ([Datenbank im Stapel](#datenbank-im-stapel)).

### `docker exec` in den Backend-Container schlägt mit „executable file not found" fehl

Das Image enthält keine Shell; die Wege ohne Shell stehen unter
[Diagnose im Container](#diagnose-im-container).

### Jeder Upload endet mit „Interner Serverfehler" und `AccessDeniedException: /app/uploads/…`

Das Host-Verzeichnis gehört nicht der Kennung `65532` — meist, weil Docker es selbst angelegt hat.
Behebung unter [„Nicht-root-Betrieb des Backend-Containers"](#nicht-root-betrieb-des-backend-containers).

### Umgebungsvariablen-Änderungen treten nicht in Kraft

`docker compose up -d <service>` statt `restart` verwenden; `restart` behält die alte Umgebung.

### Das Backend startet nicht und meldet `refuses to start`

Ein Wert des Konfigurationsabdrucks oder die Version weicht von einer Instanz ab, die laut
Instanzregister noch läuft. Im Einzelbetrieb ist das der eigene Vorgänger, wenn er hart beendet wurde
(Exit-Code `137`) und sich nicht abmelden konnte. Er gilt nach `OPAA_INSTANCE_DEAD_AFTER` als
beendet; danach `docker compose up -d backend` erneut ausführen (siehe
[Instanzregister](betrieb.md#instanzregister)).

### Der Container meldet `137`

`docker inspect --format '{{.State.OOMKilled}}' <container>` zeigt, ob die Speichergrenze der Grund
war ([Speicher und Container-Grenzen](betrieb.md#speicher-und-container-grenzen)); sonst lief die
Stopp-Frist `OPAA_STOP_GRACE_PERIOD` ab.

## Stoppen

```bash
docker compose down
```
