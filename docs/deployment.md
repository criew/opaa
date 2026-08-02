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
- **Zugriff:** Die Instanz läuft mit `OPAA_AUTH_MODE=oidc` hinter Keycloak (siehe [Authentifizierung](#authentifizierung)). Der Zugang ist bewusst account-gebunden — ein anonymer Zugang oder Gastzugang ist **nicht** vorgesehen; jede Nutzung erfordert eine Anmeldung. Wer welchen Zugang erhält, ist außerhalb dieses Dokuments geregelt und hier nicht beschrieben. Eine Konsequenz dieser Festlegung: Inhalte auf der Instanz — etwa ein dort ausgerollter Demo-Korpus (siehe #230) — sind nur für angemeldete Nutzer sichtbar, nicht öffentlich ohne Anmeldung einsehbar.
- **Aktualisierung:** Der Workflow [`publish-images.yml`](../.github/workflows/publish-images.yml) baut bei jedem Push auf `main` neue `ghcr.io/criew/opaa-backend`- und `ghcr.io/criew/opaa-frontend`-Images und veröffentlicht sie mit den Tags `main` und `sha-<commit>` in der GHCR-Registry (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr) oben). Die Testinstanz aktualisiert sich bewusst **automatisch mit diesen Images** — ein Push auf `main` reicht aus, damit die Instanz zeitnah den neuen Stand ausliefert. Der genaue Mechanismus, mit dem der VPS neue Images zieht (z. B. Watchtower, Cronjob, Webhook), ist nicht Teil dieses Repositorys und hier **nicht dokumentiert**.
- **Konfigurationsabweichungen vom Compose-Standard:** Nur der Auth-Modus (`oidc` statt `mock`) ist belegt. Welche LLM- und Embedding-Anbieter (`OPAA_AI_CHAT_PROVIDER`, `OPAA_AI_EMBEDDING_PROVIDER`) sowie welche Rate-Limit-, Bind-Adress- und Port-Einstellungen die Instanz tatsächlich verwendet, ist **nicht dokumentiert und ungeklärt** — nicht mit dem Stack-Default aus der Tabelle unten verwechseln. Der Stack-Default ohne explizite Konfiguration wäre `OPAA_AI_CHAT_PROVIDER=ollama` mit Modell `phi3:mini` und `OPAA_AI_EMBEDDING_PROVIDER=ollama` mit Modell `nomic-embed-text`; ob die Instanz diesen Default oder eine andere Konfiguration (z. B. OpenAI) verwendet, ist offen und muss beim Betreiber erfragt werden.
- **Daten:** Es dürfen dort **keine personenbezogenen, vertraulichen oder produktiven Organisationsdaten** abgelegt werden. Die Instanz ist ausschließlich für Demo- und Testzwecke mit unkritischen Beispieldaten vorgesehen.

## Schnellstart

```bash
# 1. Umgebung konfigurieren
cp .env.example .env.docker
# .env.docker bearbeiten und OPAA_OPENAI_API_KEY setzen

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

Die einzige Variable, die vor dem Start gesetzt werden muss:

```env
OPAA_OPENAI_API_KEY=sk-your-key-here
```

### Docker-spezifische Variablen

Diese Variablen sind wichtig, wenn mit Docker Compose ausgeführt wird, und sollten in `.env.docker` gesetzt werden:

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker` (oder `docker,oidc`) | Aktiviert Docker-spezifische Konfiguration (DB-URL, Ollama-URL) |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend muss an alle Schnittstellen binden, um von anderen Containern erreichbar zu sein |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Muss mit dem Host-Port des Frontends übereinstimmen |
| `OPAA_DB_USERNAME` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |
| `OPAA_DB_PASSWORD` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |

### Minimale `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_AI_CHAT_PROVIDER=openai
OPAA_AI_EMBEDDING_PROVIDER=openai
OPAA_OPENAI_API_KEY=sk-your-key-here
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
```

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
| `OPAA_AI_CHAT_PROVIDER` | `openai` | Chat-Modell-Anbieter (`openai` oder `ollama`) |
| `OPAA_AI_EMBEDDING_PROVIDER` | `openai` | Embedding-Modell-Anbieter (`openai` oder `ollama`) |
| `OPAA_OPENAI_API_KEY` | — | OpenAI-API-Schlüssel (erforderlich bei Verwendung von OpenAI) |
| `OPAA_OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI-kompatible API-Basis-URL |
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
| `OPAA_AUTH_MODE` | `mock` | Auth-Modus: `mock`, `basic` oder `oidc` |
| `OPAA_AUTH_BASIC_USERNAME` | `admin` | Benutzername für Basic Auth |
| `OPAA_AUTH_BASIC_PASSWORD` | `admin` | Passwort für Basic Auth |
| `OPAA_AUTH_BASIC_SECRET` | — | JWT-Signing-Secret (min. 256 Bit) |
| `OPAA_AUTH_BASIC_TOKEN_EXPIRATION` | `3600` | JWT-Token-Ablauf in Sekunden |
| `OPAA_AUTH_BASIC_ISSUER` | `opaa-basic` | JWT-Issuer-Claim |
| `OPAA_INITIAL_ADMIN_EMAIL` | — | E-Mail für automatisch erstellten initialen Admin-Benutzer |
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

Standardmäßig verwendet OPAA OpenAI. `OPAA_OPENAI_API_KEY` auf Ihren API-Schlüssel setzen.

Um stattdessen Ollama (lokales LLM) zu verwenden:

```env
OPAA_AI_CHAT_PROVIDER=ollama
OPAA_AI_EMBEDDING_PROVIDER=ollama
OPAA_OLLAMA_BASE_URL=http://localhost:11434
```

### vLLM / OpenAI-kompatible Server

Bei Verwendung von vLLM oder anderen OpenAI-kompatiblen Servern, die HTTP/2 nicht unterstützen, HTTP/1.1-Modus aktivieren:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

Dies ist erforderlich, weil Spring Boots Standard-HTTP-Client HTTP/2 bevorzugt, was bei Uvicorn-basierten Servern wie vLLM zu Verbindungsfehlern führt.

## Authentifizierung

### Mock-Modus (Standard)

Keine Authentifizierung — alle Anfragen sind erlaubt. Nur für lokale Entwicklung geeignet.

### Basic Auth

```env
SPRING_PROFILES_ACTIVE=docker
OPAA_AUTH_MODE=basic
OPAA_AUTH_BASIC_USERNAME=admin
OPAA_AUTH_BASIC_PASSWORD=admin
OPAA_AUTH_BASIC_SECRET=change-me-to-a-256-bit-secret-key-in-production!!
```

### OIDC (Keycloak)

Um OIDC-Authentifizierung mit dem gebündelten Keycloak zu aktivieren:

```bash
docker compose --profile oidc up --build
```

Erforderliche Variablen in `.env.docker`:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_AUTH_MODE=oidc
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

### OIDC: "Completing sign in..." hängt oder fällt auf Mock zurück

- Sicherstellen, dass Keycloak läuft (`docker compose --profile oidc ps`)
- Wenn Keycloak neu gestartet wurde, ist das Access-Token möglicherweise abgelaufen — Seite neu laden und erneut anmelden
- Prüfen, dass `OPAA_OIDC_JWK_SET_URI` `keycloak:8180` (nicht `localhost:8180`) verwendet

### Umgebungsvariablen-Änderungen treten nicht in Kraft

Nach dem Ändern von `.env.docker` `docker compose up -d <service>` (nicht `restart`) verwenden, um den Container mit der neuen Umgebung neu zu erstellen. `restart` verwendet den vorhandenen Container erneut und ignoriert `.env.docker`-Änderungen.

## Stoppen

```bash
docker compose down
```
