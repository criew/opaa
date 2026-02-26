# MVP Verification

This document maps each MVP success criterion (from [MVP.md](./MVP.md)) to its verification method — automated tests, CI pipeline checks, and manual smoke tests.

---

## Success Criteria Verification Matrix

| # | Criterion | Automated | Manual |
|---|-----------|-----------|--------|
| 1 | Indexing works | `DocumentIndexingIntegrationTest` | Docker Compose smoke test |
| 2 | Q&A works end-to-end | `QueryIntegrationTest`, `OpenAiIntegrationTest` | Browser test via Docker Compose |
| 3 | Sources are shown | `QueryIntegrationTest` (asserts sources) | Visual check in Web UI |
| 4 | Dual LLM support (OpenAI + Ollama) | `OpenAiIntegrationTest`, `ProviderConfigurationTest` | Docker Compose with Ollama |
| 5 | Separate configs (LLM + embedding) | `ProviderConfigurationTest`, `MixedProviderConfigurationTest` | — |
| 6 | Docker Compose runs | — | Smoke test checklist below |
| 7 | Local dev works | CI pipeline (backend + frontend build) | Local dev checklist below |
| 8 | UI placeholders visible | Frontend component tests | Visual check in Web UI |

---

## Automated Test Overview

### Backend Integration Tests (Testcontainers)

All tests use Testcontainers with PostgreSQL 18 + pgvector. Docker must be running.

| Test Class | What It Verifies |
|-----------|-----------------|
| `DocumentIndexingIntegrationTest` | Markdown/TXT/PDF/DOCX indexing, chunking, embedding storage, re-indexing |
| `QueryIntegrationTest` | Question answering with sources, metadata, empty results handling |
| `ProviderConfigurationTest` | Default OpenAI config, Ollama config availability, independent provider properties |
| `MixedProviderConfigurationTest` | Application loads with different chat and embedding providers |
| `OpenAiIntegrationTest` | Full end-to-end with real OpenAI API (requires `OPAA_OPENAI_API_KEY`) |

Run all backend tests:

```bash
cd backend && ./gradlew build
```

Run OpenAI integration tests (requires API key):

```bash
OPAA_OPENAI_API_KEY=sk-... ./gradlew test --tests "io.opaa.integration.*"
```

### CI Pipeline

The GitHub Actions pipeline (`.github/workflows/ci.yml`) runs on every push and PR to `main`:

- **backend**: `./gradlew build` (includes spotlessCheck, unit tests, Testcontainers tests)
- **backend-integration**: OpenAI integration tests (only when `OPAA_OPENAI_API_KEY` secret is configured)
- **frontend**: `npm run format:check` + `npm run lint` + `npm run test` + `npm run build`

---

## Docker Compose Smoke Test Checklist

### Prerequisites

- Docker and Docker Compose installed
- OpenAI API key (or Ollama running locally)

### Steps

1. **Create `.env` file** in the project root:

   ```
   OPAA_OPENAI_API_KEY=sk-your-key-here
   ```

2. **Start the stack:**

   ```bash
   docker compose up --build -d
   ```

3. **Verify all containers are running:**

   ```bash
   docker compose ps
   ```

   Expected: `opaa-postgres` (healthy), `opaa-backend` (running), `opaa-frontend` (running)

4. **Check backend health:**

   ```bash
   curl http://localhost:8080/api/health
   ```

   Expected: `200 OK`

5. **Place test documents** in `./documents/` directory (or the configured path)

6. **Trigger indexing:**

   ```bash
   curl -X POST http://localhost:8080/api/v1/indexing/trigger
   ```

   Expected: `200 OK` with job status

7. **Check indexing status:**

   ```bash
   curl http://localhost:8080/api/v1/indexing/status
   ```

   Expected: `200 OK` with `"status": "COMPLETED"`

8. **Ask a question:**

   ```bash
   curl -X POST http://localhost:8080/api/v1/query \
     -H "Content-Type: application/json" \
     -d '{"question": "What information is in the documents?"}'
   ```

   Expected: JSON response with `answer`, `sources` (with file names and scores), and `metadata`

9. **Open Web UI** at `http://localhost` and verify:
   - [ ] Chat interface loads
   - [ ] Can type and submit a question
   - [ ] Answer is displayed with source references
   - [ ] Feedback buttons (thumbs up/down) are visible
   - [ ] Access level badges are visible on sources

10. **Stop the stack:**

    ```bash
    docker compose down
    ```

---

## Local Development Checklist

### Prerequisites

- Java 21 (e.g., Eclipse Temurin)
- Node.js 20+
- Docker (for PostgreSQL via Testcontainers or standalone container)
- OpenAI API key (optional — use mock profile for development without it)

### Setup

1. **Start PostgreSQL:**

   ```bash
   docker run -d --name opaa-postgres \
     -e POSTGRES_DB=opaa \
     -e POSTGRES_USER=opaa \
     -e POSTGRES_PASSWORD=opaa \
     -p 5432:5432 \
     pgvector/pgvector:pg18
   ```

2. **Start backend:**

   ```bash
   cd backend

   # With mock data (no LLM needed):
   ./gradlew bootRun --args='--spring.profiles.active=mock'

   # With real LLM (requires OpenAI key or running Ollama):
   OPAA_OPENAI_API_KEY=sk-... ./gradlew bootRun
   ```

3. **Start frontend:**

   ```bash
   cd frontend
   npm ci

   # With MSW mocks (no backend needed):
   VITE_ENABLE_MOCKS=true npm run dev

   # Against real backend (backend must be running on :8080):
   npm run dev
   ```

4. **Open** `http://localhost:5173` in a browser

### Verification

- [ ] Backend starts without errors on port 8080
- [ ] Frontend starts without errors on port 5173
- [ ] `curl http://localhost:8080/api/health` returns 200
- [ ] Chat interface works in the browser
- [ ] Backend tests pass: `cd backend && ./gradlew build`
- [ ] Frontend tests pass: `cd frontend && npm run test -- --run`
