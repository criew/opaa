# Deployment

## Deployment aus vorgebauten Images (GHCR)

Für Zielsysteme, auf denen nicht aus dem Quellcode gebaut werden soll, veröffentlicht CI bei jedem Push auf `main` fertige Container-Images:

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `main`, `sha-<commit>` |

Auf dem Zielsystem wird kein Repository-Checkout benötigt — es genügt eine `docker-compose.yml`, die `image:` statt `build:` verwendet:

```yaml
services:
  backend:
    image: ghcr.io/criew/opaa-backend:main
  frontend:
    image: ghcr.io/criew/opaa-frontend:main
```

Aktualisieren auf den neuesten `main`-Stand:

```bash
docker compose pull && docker compose up -d
```

`main` folgt dem jeweils letzten Stand; für reproduzierbare Deployments stattdessen einen `sha-<commit>`-Tag pinnen.

## Öffentliche Testinstanz

Unter **https://opaa.ewerlin.com** betreibt der Maintainer eine öffentliche **Test-/Demo-Instanz** von OPAA. Es handelt sich ausdrücklich nicht um einen Produktivbetrieb — es gelten keine Verfügbarkeits- oder Datenerhaltungsgarantien.

- **Betreiber:** Der Maintainer (`criew`), auf privater VPS-Infrastruktur außerhalb dieses Repositorys.
- **Zweck:** Öffentlich erreichbare Instanz zum Ausprobieren und Vorzeigen des aktuellen `main`-Stands.
- **Zugriff:** Die Instanz läuft im Auth-Modus `oidc` hinter Keycloak (siehe [Authentifizierung](#authentifizierung)). Der Zugang ist bewusst account-gebunden — ein anonymer Zugang oder Gastzugang ist **nicht** vorgesehen; jede Nutzung erfordert eine Anmeldung. Wer welchen Zugang erhält, ist außerhalb dieses Dokuments geregelt und hier nicht beschrieben. Eine Konsequenz dieser Festlegung: Inhalte auf der Instanz — etwa ein dort ausgerollter Demo-Korpus (siehe #230) — sind nur für angemeldete Nutzer sichtbar, nicht öffentlich ohne Anmeldung einsehbar.
- **Administration:** Genau ein Konto trägt die Rolle `SYSTEM_ADMIN` — das persönliche Konto des Maintainers, auf das `OPAA_INITIAL_ADMIN_EMAIL` zeigt. Alle administrativen Vorgänge auf der Instanz (insbesondere das Auslösen der Indizierung) führt dieses Konto über den Admin-Bereich der Oberfläche aus. Weitere Konten mit dieser Rolle gibt es derzeit nicht.
- **Netzwerk:** Alle Container-Ports binden ausschließlich auf `127.0.0.1`. Nach außen führt ausschließlich ein nginx auf dem Host, der TLS terminiert und weiterleitet. Keycloak ist unter dem Pfad **`/idp`** eingehängt — ausdrücklich **nicht** unter `/auth`, weil das Frontend `/auth/callback` selbst als OIDC-Redirect verwendet und die beiden sich sonst überlagern.
- **Betriebsart:** Die Instanz läuft ausschließlich aus vorgebauten GHCR-Images (`ghcr.io/criew/opaa-backend:main`, `ghcr.io/criew/opaa-frontend:main`). Auf dem Server gibt es **keinen Repository-Checkout** und keinen Build — nur eine `docker-compose.yml`, die `image:` statt `build:` verwendet.
- **Daten:** Es dürfen dort **keine personenbezogenen, vertraulichen oder produktiven Organisationsdaten** abgelegt werden. Die Instanz ist ausschließlich für Demo- und Testzwecke mit unkritischen Beispieldaten vorgesehen.

#### Modellkonfiguration der Instanz

Hier ist eine Verwechslung angelegt, die bereits mehrfach zu falschen Aussagen geführt hat und deshalb ausdrücklich benannt wird:

| | Anbieter | Modell | Anmerkung |
|---|---|---|---|
| **Chat** | Anthropic | `claude-haiku-4-5` | Angebunden über Anthropics **OpenAI-kompatible Schicht**. `OPAA_AI_CHAT_PROVIDER` steht deshalb formal auf `openai`; nur `OPAA_OPENAI_BASE_URL`, der API-Schlüssel und `OPAA_OPENAI_CHAT_MODEL` zeigen auf Anthropic. |
| **Embedding** | Ollama, lokal | `nomic-embed-text` | 768 Dimensionen, entsprechend `OPAA_PGVECTOR_DIMENSIONS=768` statt des Stack-Defaults 1536. |

Drei Punkte dazu:

- **Der Wert `openai` in `OPAA_AI_CHAT_PROVIDER` bezeichnet hier das Protokoll, nicht den Anbieter.** Wer ihn als Anbieterangabe liest, kommt zu einem falschen Ergebnis — genau das ist in der Vergangenheit passiert.
- **Die Aufteilung Chat bei Anthropic, Embedding lokal ist dauerhaft, nicht provisorisch.** Anthropic bietet keine Embeddings-API an; ein einheitlicher Anbieter für beides ist mit dieser Wahl gar nicht möglich.
- Anthropic bezeichnet die OpenAI-kompatible Schicht ausdrücklich als Werkzeug zum Testen und Vergleichen, nicht als produktionsreifen Zugang. Für eine Testinstanz ist das angemessen; für einen Dauerbetrieb wäre die native Anbindung zu wählen.

**Kostenseite:** Token-Kosten entstehen ausschließlich beim Chat. Die Einbettung läuft lokal über Ollama und kostet nichts — eine Neuindizierung des Korpus ist deshalb kostenlos, unabhängig von seiner Größe.

Die Rate-Limit-Werte der Instanz sind hier nicht festgehalten.

### Korpus einspielen und indizieren

Der Dokumentenbestand der Instanz liegt **nicht** im Repository, sondern in einem Verzeichnis auf dem VPS, das über `OPAA_INDEXING_DOCUMENT_PATH_HOST` in den Backend-Container unter `/app/documents` gemountet wird (siehe [Dokumente](#dokumente)). Das Verzeichnis wird manuell befüllt.

1. Dateien vom Arbeitsrechner in das gemountete Verzeichnis auf dem VPS übertragen, per `rsync` oder `scp`:

   ```bash
   rsync -av --delete ./corpus/ <benutzer>@<host>:<dokumentenverzeichnis>/
   ```

   > **Bewusst ohne konkrete Angaben:** `criew/opaa` ist ein öffentliches Repository. Host, Benutzername und der Pfad des Dokumentenverzeichnisses auf dem Server stehen deshalb nicht hier, sondern in der Betriebsdokumentation des Maintainers. Wer den Rollout ausführen soll, bekommt sie von ihm. Beschrieben ist hier das Verfahren, nicht die Belegung.

2. Ein Neustart des Backends ist nicht nötig: Das Verzeichnis ist ein Bind-Mount, neue Dateien sind sofort im Container sichtbar.
3. Die Indizierung löst der Inhaber der Rolle `SYSTEM_ADMIN` über den **Admin-Bereich der Oberfläche** aus. Das Feld „URL" bleibt dabei **leer** — nur dann indiziert OPAA das gemountete Verzeichnis. Ein ausgefülltes URL-Feld schaltet stattdessen auf das Crawlen einer entfernten Verzeichnisauflistung um.
4. Der Fortschritt ist im Admin-Bereich sichtbar (dahinter `GET /api/v1/indexing/status`).

Unveränderte Dateien werden anhand ihrer SHA-256-Prüfsumme übersprungen; ein erneuter Lauf über denselben Bestand ist deshalb billig und gefahrlos.

### Aktualisierung auf einen neuen `main`-Stand

Der Workflow [`publish-images.yml`](../.github/workflows/publish-images.yml) baut bei jedem Push auf `main` neue `ghcr.io/criew/opaa-backend`- und `ghcr.io/criew/opaa-frontend`-Images und veröffentlicht sie mit den Tags `main` und `sha-<commit>` in der GHCR-Registry (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr) oben).

Auf dem Server liegt ein **Deployment-Skript**, das genau das tut: die aktuellen Images ziehen und den Stack auf den neuen Stand bringen. Es kennt zusätzlich einen Schalter, der auch die Volumes verwirft — damit ist die Datenbank und mit ihr der gesamte Index weg. Dieser Schalter ist deshalb kein Aktualisierungs-, sondern ein Neuaufsetzschritt; danach ist zwingend eine vollständige Neuindizierung nötig.

Ein **Cron-Job ruft dieses Skript täglich um 2 Uhr morgens auf** — ohne den zurücksetzenden Schalter, die Daten bleiben also erhalten. Die Ausgabe der Läufe wird protokolliert und wöchentlich rotiert. Die Instanz folgt dem `main`-Stand damit mit höchstens einem Tag Verzug; ein Push auf `main` erscheint nicht sofort, sondern beim nächsten nächtlichen Lauf. Wer schneller sein will, ruft das Skript von Hand auf.

Ohne das Skript entspricht der Ablauf diesen Schritten, ausgeführt im Verzeichnis mit der `docker-compose.yml` der Instanz:

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

#### Was ein Update mit dem Index macht

Kurz: Ein Update über `docker compose pull` + `docker compose up -d` **gefährdet den Index nicht** und macht **keine Neuindizierung erforderlich**. Im Einzelnen:

- **Der Korpus** liegt in einem Bind-Mount auf dem Host und ist von Container-Neustarts unberührt.
- **Der Index** liegt in PostgreSQL, dessen Daten im benannten Volume `opaa-postgres-data` liegen. Das Volume überlebt das Neuerstellen der Container; nur `docker compose down -v` löscht es.
- **Liquibase** wendet beim Backend-Start ausschließlich noch nicht angewendete Changesets vorwärts an. Keines der Changesets löscht Dokument- oder Vektordaten — die `dropTable`-Anweisungen in `db/changelog/changes/` stehen ausnahmslos in `rollback`-Blöcken und laufen im Normalbetrieb nie.
- **Die Vektortabelle** wird nicht von Liquibase, sondern von Spring AI selbst angelegt (`spring.ai.vectorstore.pgvector.initialize-schema: true`). Sie wird nur erzeugt, wenn sie fehlt, und bei einem Update nicht verändert.

> **Der Vektorspeicher ist nicht wählbar.** OPAA speichert Vektoren in PostgreSQL mit pgvector; das ist der einzige unterstützte Vektorspeicher. Der Zugriff läuft zwar über eine portable Schnittstelle von Spring AI, ein Wechsel wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert. Begründung: [Daten-Indizierung & RAG](./features/data-indexing-rag.md#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner).

Eine Neuindizierung wird erst durch Änderungen nötig, die nichts mit dem Image-Update zu tun haben:

| Auslöser | Folge |
|---|---|
| Embedding-Modell oder -Anbieter gewechselt | Bestehende Vektoren stammen aus einem anderen Modell und sind nicht mehr vergleichbar — vollständige Neuindizierung nötig. **`OPAA_PGVECTOR_DIMENSIONS` muss mitgezogen werden**, sonst passt die Vektorbreite nicht zum neuen Modell |
| `OPAA_PGVECTOR_DIMENSIONS` geändert | Passt nicht mehr zur bestehenden Vektortabelle — die Datenbank muss zurückgesetzt und der Korpus neu indiziert werden |
| `docker compose down -v` bzw. das Deployment-Skript mit zurücksetzendem Schalter ausgeführt | Datenbank inklusive Index ist weg — vollständige Neuindizierung nötig |
| PostgreSQL-Hauptversion gewechselt (Image-Tag von `pg18` auf eine höhere Version) | Das Datenverzeichnis im Volume ist nicht aufwärtskompatibel; ein solcher Wechsel ist ein eigener Migrationsvorgang, kein `docker compose pull` |

Auf der Testinstanz sind `nomic-embed-text` und `OPAA_PGVECTOR_DIMENSIONS=768` fest aneinander gekoppelt: Wer das Embedding-Modell wechselt, muss beide Werte gemeinsam ändern und die Datenbank zurücksetzen. Ein Wechsel des **Chat**-Modells berührt den Index dagegen nicht — Chat und Einbettung sind auf der Instanz ohnehin getrennte Anbieter.

> **Falle bei einer Neuindizierung:** OPAA überspringt Dateien, deren SHA-256-Prüfsumme unverändert ist **und** deren Datensatz in der Tabelle `documents` den Status `INDEXED` trägt. Wird die Vektortabelle geleert, ohne auch `documents` zu bereinigen, meldet ein neuer Lauf lauter übersprungene Dateien und der Index bleibt leer. Beide Tabellen liegen in derselben Datenbank — wer den Index verwirft, muss `documents` mitverwerfen.

### Sicherheitshinweis: `POST /api/v1/indexing/trigger` ist von außen erreichbar

Der Endpunkt ist auf der Testinstanz aus dem Internet erreichbar. Dass alle Container-Ports nur auf `127.0.0.1` binden, ändert daran nichts — der nach außen gerichtete nginx reicht die API-Pfade durch, und der Indizierungspfad ist davon nicht ausgenommen. Die Bindung auf `127.0.0.1` verhindert lediglich, dass jemand die Container unter Umgehung des nginx direkt anspricht.

Er ist durch Keycloak authentifiziert und zusätzlich auf die Rolle `SYSTEM_ADMIN` beschränkt (`@PreAuthorize("hasRole('SYSTEM_ADMIN')")` in `IndexingController`); zusätzlich greift das Rate Limiting mit einem eigenen, engen Kontingent für diesen Pfad (`OPAA_RATE_LIMIT_INDEXING_*`, standardmäßig eine Anfrage pro IP und Minute).

Wer den Endpunkt aufrufen darf, kann im Feld `url` eine beliebige Adresse angeben, die der Server dann abruft und crawlt. Das schließt Ziele ein, die nur aus dem Netz des VPS erreichbar sind und nicht aus dem Internet. Fachlich ist das serverseitige Anfragefälschung (Server-Side Request Forgery, SSRF) — hier allerdings als **Eigenschaft der Funktion**, nicht als Fehler: Der Endpunkt existiert genau dafür, entfernte Dokumentenquellen zu erschließen, und er verlangt Anmeldung plus Adminrolle.

**Ist-Zustand der Einschränkungen** (Stand dieser Datei, geprüft an `IndexingController`, `UrlIndexingExecutor` und `AutoindexCrawlerService`):

- Das Schema ist faktisch auf `http` und `https` begrenzt, weil der verwendete `java.net.http.HttpClient` andere Schemata ablehnt. Eine eigene Schema-Prüfung im OPAA-Code gibt es **nicht**.
- Eine **Blockliste privater oder lokaler Adressbereiche existiert nicht**. `localhost`, `127.0.0.1`, `10.0.0.0/8`, `192.168.0.0/16` und Link-Local-Adressen wie `169.254.169.254` werden nicht gesondert behandelt.
- Weiterleitungen werden gefolgt (`HttpClient.Redirect.NORMAL`).
- Mit `insecureSsl: true` lässt sich die Zertifikatsprüfung für den Aufruf abschalten.
- Fehlermeldungen des Crawls landen im Jobstatus und sind über `GET /api/v1/indexing/status` lesbar; sie unterscheiden erreichbare von nicht erreichbaren Zielen.

**Risikoeinordnung.** Heute klein: Nur der Maintainer besitzt die Rolle `SYSTEM_ADMIN`, und er ist zugleich Betreiber des VPS — er kann über SSH ohnehin alles, was der Endpunkt ermöglicht. Größer wird das Risiko, sobald **weitere Konten die Rolle `SYSTEM_ADMIN` erhalten**, insbesondere Konten von Personen, die keinen Zugriff auf den Server selbst haben sollen. Ein anonymer Zugang oder Gastzugang würde das Risiko deutlich vergrößern, ist für diese Instanz aber ausdrücklich ausgeschlossen (siehe `docs/features/search-quality-evaluation.md`, Abschnitt „Zugangsmodell").

**Konsequenz für den Betrieb:** Die Rolle `SYSTEM_ADMIN` ist auf der Testinstanz wie ein Serverzugang zu behandeln und entsprechend sparsam zu vergeben. Eine Zielprüfung im Code (Blockliste privater Adressbereiche) wird als Härtung für den Fall weiterer Adminkonten in #267 geführt.

## Schnellstart

```bash
# 1. Umgebung konfigurieren
cp .env.example .env.docker
# .env.docker bearbeiten. Voreingestellt sind lokal betriebene Modelle über
# Ollama — dafür ist keine weitere Angabe nötig. Wer stattdessen einen
# openai-kompatiblen Anbieter wählt, muss OPAA_OPENAI_BASE_URL setzen
# (siehe „LLM-Anbieter").

# 2. Alle Services starten
docker compose up --build

# 3. Anwendung öffnen
# Frontend: http://localhost:3000
# Backend-API: http://localhost:8081/api
```

## Services

| Service    | Host-Port | Container-Port | Beschreibung                          |
|------------|-----------|----------------|---------------------------------------|
| frontend   | 3000      | 80             | React-App über Nginx bereitgestellt   |
| backend    | 8081      | 8080           | Spring Boot API                       |
| postgres   | 5432      | 5432           | PostgreSQL 18 mit pgvector            |
| keycloak   | 8180      | 8180           | Keycloak (nur OIDC-Profil)            |

## Konfiguration

Alle Konfigurationen erfolgen über Umgebungsvariablen in `.env.docker`. Docker Compose lädt diese Datei über die `env_file`-Direktive. Alle verfügbaren Optionen mit Beschreibungen finden Sie in `.env.example`.

> **Wichtig:** Docker Compose lädt automatisch eine `.env`-Datei (falls vorhanden) für die Variablen-Interpolation in `docker-compose.yml` selbst. Um Konflikte mit lokalen Entwicklungseinstellungen zu vermeiden, verwenden Docker-Compose-Services `.env.docker` als ihre `env_file`. Wenn Sie eine `.env`-Datei für die lokale Entwicklung haben, stellen Sie sicher, dass die Docker-relevanten Variablen (Ports, DB-Anmeldeinformationen) nicht kollidieren.

### Erforderliche Variablen

Im Standardfall — lokal betriebene Modelle über Ollama für Chat und Einbettung — ist **keine**
Modellvariable erforderlich. Der Stack startet ohne zusätzliche Angabe.

Wer für Chat oder Einbettung den openai-kompatiblen Anbieter wählt (`OPAA_AI_CHAT_PROVIDER=openai`
bzw. `OPAA_AI_EMBEDDING_PROVIDER=openai`), muss die **Zieladresse angeben**:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

Es gibt für die Adresse **keine Voreinstellung**. Fehlt sie bei gewähltem openai-kompatiblen
Anbieter, bricht das Backend den Start mit einer Meldung ab, die die fehlende Variable benennt
(`io.opaa.config.OpenAiBaseUrlGuard`). Der Grund: `openai` bezeichnet das Protokoll, nicht das Ziel
— lokal betriebene Modellserver sprechen dasselbe Protokoll. Eine Voreinstellung würde eine
Installation, die im Haus bleiben soll, stillschweigend nach außen richten.

### Docker-spezifische Variablen

Diese Variablen sind wichtig, wenn mit Docker Compose ausgeführt wird, und sollten in `.env.docker` gesetzt werden:

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker,oidc` (Betrieb) oder `docker,dev` (Entwicklung) | Aktiviert Docker-spezifische Konfiguration (DB-URL, Ollama-URL) und den Auth-Modus; ohne `oidc` oder `dev` startet das Backend nicht |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend muss an alle Schnittstellen binden, um von anderen Containern erreichbar zu sein |
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
```

Chat und Einbettung laufen damit über Ollama (`OPAA_OLLAMA_BASE_URL` ist im Profil `docker` auf
`http://ollama:11434` vorbelegt). Für einen openai-kompatiblen Anbieter kommen
`OPAA_AI_CHAT_PROVIDER` bzw. `OPAA_AI_EMBEDDING_PROVIDER`, `OPAA_OPENAI_BASE_URL` und
`OPAA_OPENAI_API_KEY` hinzu.

### Alle Umgebungsvariablen

| Variable | Standard | Beschreibung |
|----------|---------|-------------|
| **Allgemein** | | |
| `OPAA_SERVER_ADDRESS` | `localhost` | Bind-Adresse (`0.0.0.0` für Netzwerkzugang) |
| `OPAA_HTTP_FORCE_HTTP1` | `false` | HTTP/1.1 für vLLM-Kompatibilität erzwingen |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Erlaubte CORS-Origins (kommagetrennt) |
| `OPAA_INDEXING_DOCUMENT_PATH_HOST` | `./documents` | Host-Pfad für Dokumente (in Container gemountet) |
| **Datenbank** | | |
| `OPAA_DB_URL` | `jdbc:postgresql://localhost:5432/opaa` | JDBC-Verbindungs-URL |
| `OPAA_DB_USERNAME` | `opaa` | PostgreSQL-Benutzername |
| `OPAA_DB_PASSWORD` | `opaa` | PostgreSQL-Passwort |
| **LLM / Embedding** | | |
| `OPAA_AI_CHAT_PROVIDER` | `ollama` | Chat-Modell-Anbieter (`ollama` oder `openai`) |
| `OPAA_AI_EMBEDDING_PROVIDER` | `ollama` | Embedding-Modell-Anbieter (`ollama` oder `openai`) |
| `OPAA_OPENAI_API_KEY` | — | Zugangsschlüssel der openai-kompatiblen Schnittstelle |
| `OPAA_OPENAI_BASE_URL` | — | Basis-Adresse der openai-kompatiblen Schnittstelle. **Ohne Voreinstellung; erforderlich, sobald ein Anbieter auf `openai` steht** — sonst bricht der Start ab |
| `OPAA_OPENAI_CHAT_MODEL` | `gpt-4o` | OpenAI-Chat-Modellname |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `0.7` | Chat-Antwort-Temperatur (0,0–2,0) |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `2000` | Maximale Tokens in Chat-Antwort |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | OpenAI-Embedding-Modellname |
| `OPAA_OLLAMA_BASE_URL` | `http://ollama:11434` | Ollama-API-Basis-URL |
| `OPAA_OLLAMA_CHAT_MODEL` | `phi3:mini` | Ollama-Chat-Modellname |
| `OPAA_OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Ollama-Embedding-Modellname |
| **Abfrage (RAG-Retrieval)** | | |
| `OPAA_QUERY_TOP_K` | `5` | Anzahl der pro Abfrage abgerufenen Dokument-Chunks (1–100) |
| `OPAA_QUERY_SIMILARITY_THRESHOLD` | `0.3` | Minimale Kosinus-Ähnlichkeit für Chunk-Aufnahme (0,0–1,0) |
| **Indizierung** | | |
| `OPAA_INDEXING_DOCUMENT_PATH` | `./documents` | Dateisystempfad für Quelldokumente |
| `OPAA_INDEXING_CHUNK_SIZE` | `1000` | Ziel-Tokens pro Chunk (1–10.000) |
| `OPAA_INDEXING_BATCH_SIZE` | `50` | Chunks pro Embedding-API-Aufruf (1–1.000) |
| `OPAA_INDEXING_RETRY_ATTEMPTS` | `3` | Wiederholungsanzahl bei vorübergehenden Fehlern (0–10) |
| `OPAA_INDEXING_THREAD_POOL_CORE_SIZE` | `2` | Kern-Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` | `4` | Maximale Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_QUEUE_CAPACITY` | `20` | Task-Queue-Kapazität für asynchrone Indizierung |
| **pgvector** | | |
| `OPAA_PGVECTOR_DIMENSIONS` | `1536` | Vektor-Dimensionen (muss mit Embedding-Modell übereinstimmen) |
| `OPAA_PGVECTOR_DISTANCE_TYPE` | `cosine_distance` | Distanzfunktion für Ähnlichkeitssuche |
| **Rate Limiting** | | |
| `OPAA_RATE_LIMIT_ENABLED` | `true` | Rate Limiting aktivieren/deaktivieren |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `10` | Max. Abfrageanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `60` | Abfrage-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `100` | Max. Abfrageanfragen über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `1` | Max. Indizierungsanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `60` | Indizierungs-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `5` | Max. Indizierungsanfragen über alle IPs pro Fenster |
| **Authentifizierung** | | |
| `SPRING_PROFILES_ACTIVE` | — | Muss `oidc` (Betrieb) oder `dev` (Entwicklung/Tests) enthalten; ohne eines der beiden startet das Backend nicht |
| `OPAA_INITIAL_ADMIN_EMAIL` | `admin@opaa.local` | E-Mail für den automatisch erstellten initialen Admin-Benutzer |
| **Entwicklungs-Auth (`dev`)** | | |
| `OPAA_AUTH_DEV_ISSUER` | `opaa-dev` | Issuer-Claim der synthetischen Tokens |
| `OPAA_AUTH_DEV_DEFAULT_USER` | `dev-admin` | Nutzer, als der ohne `X-OPAA-Dev-User`-Header authentifiziert wird |
| **OIDC** | | |
| `OPAA_OIDC_JWK_SET_URI` | `http://localhost:8180/...` | JWK-Set-URI für Token-Verifizierung |
| `OPAA_OIDC_ISSUER_URI` | `http://localhost:8180/realms/opaa` | OIDC-Issuer-URI für Token-Validierung |
| `OPAA_OIDC_AUTHORITY` | `http://localhost:8180/realms/opaa` | OIDC-Authority-URL (vom Frontend verwendet) |
| `OPAA_OIDC_CLIENT_ID` | `opaa-frontend` | OIDC-Client-ID |
| **Docker-Compose-Ports** | | |
| `OPAA_BACKEND_PORT` | `8081` | Backend-Host-Port |
| `OPAA_FRONTEND_PORT` | `3000` | Frontend-Host-Port |

### Netzwerkzugang

Standardmäßig bindet das Backend an `localhost`. Um OPAA von anderen Geräten im Netzwerk zugänglich zu machen, setzen Sie:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

> **Hinweis:** In Docker Compose **muss** `OPAA_SERVER_ADDRESS` auf `0.0.0.0` gesetzt werden, damit das Backend vom Nginx-Reverse-Proxy des Frontend-Containers erreichbar ist.

Für die lokale Entwicklung auch das Frontend mit `npm run dev -- --host` starten und den Zugriffsursprung zu CORS hinzufügen:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

### LLM-Anbieter

**Voreingestellt sind lokal betriebene Modelle** über Ollama, für Chat und für Einbettung. Eine
Installation, an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf. Diese
Voreinstellung ist so gewollt und bleibt (siehe
[ADR-0014, Nachtrag vom 14.08.2026](decisions/0014-produktausrichtung-oeffentliche-verwaltung.md#nachträge-entschiedene-punkte)).

```env
OPAA_AI_CHAT_PROVIDER=ollama
OPAA_AI_EMBEDDING_PROVIDER=ollama
OPAA_OLLAMA_BASE_URL=http://localhost:11434
```

Um stattdessen einen openai-kompatiblen Anbieter zu verwenden, sind Anbieter **und Zieladresse**
anzugeben:

```env
OPAA_AI_CHAT_PROVIDER=openai
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

`OPAA_OPENAI_CHAT_BASE_URL` und `OPAA_OPENAI_EMBEDDING_BASE_URL` überschreiben die Adresse je
Funktion; ohne sie gilt `OPAA_OPENAI_BASE_URL` für beide.

> **Es gibt keine technische Sperre**, die einen Aufruf außerhalb festgelegter Netzbereiche
> verhindert. Wer zusichern muss, dass keine Daten das Haus verlassen, weist die Konfiguration nach
> und sichert den Netzweg außerhalb von OPAA ab — siehe
> [Modelle und zentrale Steuerung](features/llm-integration.md#was-heute-gilt-und-was-nicht-gebaut).

### vLLM / OpenAI-kompatible Server

Bei Verwendung von vLLM oder anderen OpenAI-kompatiblen Servern, die HTTP/2 nicht unterstützen, HTTP/1.1-Modus aktivieren:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

Dies ist erforderlich, weil Spring Boots Standard-HTTP-Client HTTP/2 bevorzugt, was bei Uvicorn-basierten Servern wie vLLM zu Verbindungsfehlern führt.

## Authentifizierung

OPAA kennt genau zwei Auth-Modi ([ADR-0005](decisions/0005-authentication-strategy.md)). Der Modus
wird über das aktive Spring-Profil gewählt; ist weder `oidc` noch `dev` gesetzt, **bricht das
Backend den Start mit einer Fehlermeldung ab**.

| Modus | Profil | Zweck |
|-------|--------|-------|
| `oidc` | `oidc` | Der einzige für den Betrieb zulässige Modus |
| `dev` | `dev` | Lokale Entwicklung und automatisierte Tests — **keinerlei Prüfung von Anmeldedaten** |

### Entwicklungsmodus (`dev`)

```env
SPRING_PROFILES_ACTIVE=docker,dev
```

Es gibt keinen Anmeldevorgang und kein Token: Jede Anfrage wird als einer der konfigurierten
Entwicklungsnutzer authentifiziert. Vorkonfiguriert sind `dev-admin` (E-Mail `admin@opaa.local`,
wird durch `OPAA_INITIAL_ADMIN_EMAIL` zum `SYSTEM_ADMIN`) und `dev-user` (regulärer Nutzer).

Den Nutzer wechselt man im Browser über den Query-Parameter `?devUser=dev-user`, der für die Dauer
der Browser-Session gemerkt wird; direkte API-Aufrufe setzen stattdessen den Header
`X-OPAA-Dev-User`. Ein unbekannter Nutzername führt zu `401`.

> **Warnung:** Dieser Modus deaktiviert die Authentifizierung vollständig. Er gehört ausschließlich
> auf Arbeitsplätze und in Testumgebungen, nie auf eine erreichbare Instanz. Das Backend
> protokolliert beim Start eine entsprechende Warnung.

### OIDC (Keycloak)

Um OIDC-Authentifizierung mit dem gebündelten Keycloak zu aktivieren:

```bash
docker compose --profile oidc up --build
```

Erforderliche Variablen in `.env.docker`:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_OIDC_JWK_SET_URI=http://keycloak:8180/realms/opaa/protocol/openid-connect/certs
```

> **Wichtig:** `OPAA_OIDC_JWK_SET_URI` muss den Docker-internen Hostnamen `keycloak` (nicht `localhost`) verwenden, weil der Backend-Container JWT-Tokens verifiziert, indem er Schlüssel von Keycloak abruft. `OPAA_OIDC_ISSUER_URI` und `OPAA_OIDC_AUTHORITY` sollten `http://localhost:8180/...` bleiben, da der Browser diese URLs verwendet.

Ein Testbenutzer ist im Keycloak-Realm vorkonfiguriert:
- **Benutzername:** `testuser`
- **Passwort:** `testpass`

Die Keycloak-Admin-Konsole ist unter http://localhost:8180 verfügbar (admin/admin).

## Dokumente

Dokumente im `./documents`-Verzeichnis ablegen (oder `OPAA_INDEXING_DOCUMENT_PATH_HOST` in `.env.docker` ändern). Das Verzeichnis wird in den Backend-Container unter `/app/documents` gemountet.

## Datenbank

PostgreSQL-Daten werden in einem Docker-Volume (`opaa-postgres-data`) gespeichert. Daten überleben `docker compose down`- und `docker compose up`-Zyklen.

Datenbank zurücksetzen:

```bash
docker compose down -v
```

> **Hinweis:** `docker compose down -v` muss ausgeführt werden, wenn `OPAA_DB_USERNAME` oder `OPAA_DB_PASSWORD` geändert wird, weil PostgreSQL den initialen Benutzer nur beim ersten Start erstellt. Ohne Volume-Entfernung werden Anmeldeinformationsänderungen ignoriert.

## Fehlerbehebung

### Backend gibt leere Antworten oder "Connection refused" zurück

Das Backend bindet standardmäßig an `localhost`, was nur von innerhalb des Containers erreichbar ist. `OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker` setzen.

### POST-Anfragen geben 403 Forbidden zurück

CORS ist wahrscheinlich falsch konfiguriert. Sicherstellen, dass `OPAA_CORS_ALLOWED_ORIGINS` mit der Frontend-URL übereinstimmt (z. B. `http://localhost:3000`). GET-Anfragen können funktionieren, weil sie keinen CORS-Preflight auslösen, während POST-Anfragen mit `Content-Type: application/json` dies tun.

### Passwort-Authentifizierung für Benutzer fehlgeschlagen

Das PostgreSQL-Volume enthält noch Daten von einer früheren Initialisierung mit anderen Anmeldeinformationen. `docker compose down -v` ausführen, um das Volume zu entfernen und neu zu starten.

### OIDC: "Completing sign in..." hängt

- Sicherstellen, dass Keycloak läuft (`docker compose --profile oidc ps`)
- Wenn Keycloak neu gestartet wurde, ist das Access-Token möglicherweise abgelaufen — Seite neu laden und erneut anmelden
- Prüfen, dass `OPAA_OIDC_JWK_SET_URI` `keycloak:8180` (nicht `localhost:8180`) verwendet

### Umgebungsvariablen-Änderungen treten nicht in Kraft

Nach dem Ändern von `.env.docker` `docker compose up -d <service>` (nicht `restart`) verwenden, um den Container mit der neuen Umgebung neu zu erstellen. `restart` verwendet den vorhandenen Container erneut und ignoriert `.env.docker`-Änderungen.

## Stoppen

```bash
docker compose down
```
