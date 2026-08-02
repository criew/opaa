# MVP-Verifikation

Dieses Dokument ordnet jedes MVP-Erfolgskriterium (aus [MVP.md](./MVP.md)) seiner Verifizierungsmethode zu — automatisierte Tests, CI-Pipeline-Prüfungen und manuelle Smoke-Tests.

---

## Erfolgskriterien-Verifikationsmatrix

| # | Kriterium | Automatisiert | Manuell |
|---|-----------|---------------|---------|
| 1 | Indizierung funktioniert | `DocumentIndexingIntegrationTest` | Docker-Compose-Smoke-Test |
| 2 | Frage-Antwort funktioniert durchgehend | `QueryIntegrationTest`, `OpenAiIntegrationTest` | Browser-Test über Docker Compose |
| 3 | Quellen werden angezeigt | `QueryIntegrationTest` (prüft Quellen) | Visuelle Prüfung in Web-UI |
| 4 | Duale LLM-Unterstützung (OpenAI + Ollama) | `OpenAiIntegrationTest`, `ProviderConfigurationTest` | Docker Compose mit Ollama |
| 5 | Separate Konfigurationen (LLM + Embedding) | `ProviderConfigurationTest`, `MixedProviderConfigurationTest` | — |
| 6 | Docker Compose läuft | — | Smoke-Test-Checkliste unten |
| 7 | Lokale Entwicklung funktioniert | CI-Pipeline (Backend + Frontend-Build) | Lokale Entwicklungs-Checkliste unten |
| 8 | UI-Platzhalter sichtbar | Frontend-Komponenten-Tests | Visuelle Prüfung in Web-UI |

---

## Überblick über automatisierte Tests

### Backend-Integrationstests (Testcontainers)

Alle Tests verwenden Testcontainers mit PostgreSQL 18 + pgvector. Docker muss laufen.

| Test-Klasse | Was verifiziert wird |
|-------------|---------------------|
| `DocumentIndexingIntegrationTest` | Markdown-/TXT-/PDF-/DOCX-Indizierung, Chunking, Embedding-Speicherung, Neu-Indizierung |
| `QueryIntegrationTest` | Frage-Antwort mit Quellen, Metadaten, Behandlung leerer Ergebnisse |
| `ProviderConfigurationTest` | Standard-OpenAI-Konfiguration, Ollama-Konfiguration Verfügbarkeit, unabhängige Anbieter-Eigenschaften |
| `MixedProviderConfigurationTest` | Anwendung lädt mit verschiedenen Chat- und Embedding-Anbietern |
| `OpenAiIntegrationTest` | Vollständiges End-to-End mit echter OpenAI-API (erfordert `OPAA_OPENAI_API_KEY`) |

Alle Backend-Tests ausführen:

```bash
cd backend && ./gradlew build
```

OpenAI-Integrationstests ausführen (erfordert API-Schlüssel):

```bash
OPAA_OPENAI_API_KEY=sk-... ./gradlew test --tests "io.opaa.integration.*"
```

### CI-Pipeline

Die GitHub-Actions-Pipeline (`.github/workflows/ci.yml`) läuft bei jedem Push und PR zu `main`:

- **backend**: `./gradlew build` (beinhaltet spotlessCheck, Unit-Tests, Testcontainers-Tests)
- **backend-integration**: OpenAI-Integrationstests (nur wenn `OPAA_OPENAI_API_KEY` Secret konfiguriert ist)
- **frontend**: `npm run format:check` + `npm run lint` + `npm run test` + `npm run build`

---

## Docker-Compose-Smoke-Test-Checkliste

### Voraussetzungen

- Docker und Docker Compose installiert
- OpenAI-API-Schlüssel (oder lokal laufendes Ollama)

### Schritte

1. **`.env`-Datei erstellen** im Projektstammverzeichnis:

   ```
   OPAA_OPENAI_API_KEY=sk-your-key-here
   ```

2. **Stack starten:**

   ```bash
   docker compose up --build -d
   ```

3. **Verifizieren, dass alle Container laufen:**

   ```bash
   docker compose ps
   ```

   Erwartet: `opaa-postgres` (healthy), `opaa-backend` (running), `opaa-frontend` (running)

4. **Backend-Gesundheit prüfen:**

   ```bash
   curl http://localhost:8080/api/health
   ```

   Erwartet: `200 OK`

5. **Testdokumente ablegen** im `./documents/`-Verzeichnis (oder dem konfigurierten Pfad)

6. **Indizierung auslösen:**

   ```bash
   curl -X POST http://localhost:8080/api/v1/indexing/trigger
   ```

   Erwartet: `200 OK` mit Job-Status

7. **Indizierungsstatus prüfen:**

   ```bash
   curl http://localhost:8080/api/v1/indexing/status
   ```

   Erwartet: `200 OK` mit `"status": "COMPLETED"`

8. **Eine Frage stellen:**

   ```bash
   curl -X POST http://localhost:8080/api/v1/query \
     -H "Content-Type: application/json" \
     -d '{"question": "What information is in the documents?"}'
   ```

   Erwartet: JSON-Antwort mit `answer`, `sources` (mit Dateinamen und Scores) und `metadata`

9. **Web-UI öffnen** unter `http://localhost` und verifizieren:
   - [ ] Chat-Schnittstelle lädt
   - [ ] Frage kann eingegeben und gesendet werden
   - [ ] Antwort wird mit Quellenreferenzen angezeigt
   - [ ] Feedback-Buttons (Daumen hoch/runter) sind sichtbar
   - [ ] Zugangsstufen-Abzeichen sind auf Quellen sichtbar

10. **Stack stoppen:**

    ```bash
    docker compose down
    ```

---

## Lokale Entwicklungs-Checkliste

### Voraussetzungen

- Java 21 (z. B. Eclipse Temurin)
- Node.js 22+ (siehe `frontend/.nvmrc`)
- Docker (für PostgreSQL über Testcontainers oder eigenständigen Container)
- OpenAI-API-Schlüssel (optional — Ollama für lokale Entwicklung ohne verwenden)

### Setup

1. **PostgreSQL starten:**

   ```bash
   docker run -d --name opaa-postgres \
     -e POSTGRES_DB=opaa \
     -e POSTGRES_USER=opaa \
     -e POSTGRES_PASSWORD=opaa \
     -p 5432:5432 \
     pgvector/pgvector:pg18
   ```

2. **Backend starten:**

   ```bash
   cd backend

   # Mit Ollama (lokal, kein API-Schlüssel nötig):
   ./gradlew bootRun

   # Mit OpenAI (erfordert API-Schlüssel):
   OPAA_OPENAI_API_KEY=sk-... ./gradlew bootRun
   ```

3. **Frontend starten:**

   ```bash
   cd frontend
   npm ci

   # Mit MSW-Mocks (kein Backend nötig):
   VITE_ENABLE_MOCKS=true npm run dev

   # Gegen echtes Backend (Backend muss auf :8080 laufen):
   npm run dev
   ```

4. **Öffnen** `http://localhost:5173` im Browser

### Verifikation

- [ ] Backend startet ohne Fehler auf Port 8080
- [ ] Frontend startet ohne Fehler auf Port 5173
- [ ] `curl http://localhost:8080/api/health` gibt 200 zurück
- [ ] Chat-Schnittstelle funktioniert im Browser
- [ ] Backend-Tests bestehen: `cd backend && ./gradlew build`
- [ ] Frontend-Tests bestehen: `cd frontend && npm run test -- --run`
