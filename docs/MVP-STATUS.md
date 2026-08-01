# MVP-Status nach Feature-Bereich

**Zuletzt aktualisiert:** 2026-03-01
**MVP-Status:** VOLLSTÄNDIG

Dieses Dokument verfolgt den MVP-Implementierungsstatus für jeden großen Feature-Bereich. Es dient als Referenz dafür, was aktuell gebaut ist und was für zukünftige Releases geplant ist.

---

## Kurzübersicht

| Feature-Bereich | MVP-Status | Vollständigkeit | Nächste Phase |
|---|---|---|---|
| **Daten-Indizierung & RAG** | Vollständig | 100% | Konnektor-Ökosystem |
| **LLM-Integration** | Vollständig | 100% | Erweiterte Reasoning-Modelle |
| **Benutzer-Frontends** | Vollständig | 100% | Chat-Plattform-Integrationen |
| **Zugangskontrolle & Workspaces** | Vollständig | 100% | Feinkörnige Berechtigungen |
| **Deployment** | Vollständig | 100% | Hochverfügbarkeits-Setup |

---

## Feature-Bereiche

### 1. Daten-Indizierung & RAG

**MVP-Umfang:**
- Dokument-Upload über Web-UI, REST-API und direkter Dateisystem-Aufnahme
- Unterstützte Formate: Markdown, TXT, PDF, DOCX, XLSX, PPTX
- Dokument-Chunking und semantisches Embedding (über LLM-Embeddings)
- Retrieval-Pipeline mit Relevanz-Ranking
- Quellenangabe und Metadaten-Erhaltung
- Dokument-Neu-Indizierung und -Aktualisierungen

**Nicht im MVP (Geplant):**
- Konnektor-Ökosystem (Confluence, Notion, Jira, GitHub, E-Mail)
- Geplante/inkrementelle Aufnahme aus externen Quellen
- OCR für gescannte PDFs
- Erweiterte Chunking-Strategien (semantisch, hierarchisch)

**Test-Coverage:**
- `DocumentIndexingIntegrationTest` — Chunking, Embedding, Speicherung
- `QueryIntegrationTest` — Retrieval-Genauigkeit, Quellen-Tracking
- Frontend-Komponenten-Tests für Upload-UI

**Wichtige Dateien:**
- Backend: `backend/src/main/java/io/opaa/indexing/`
- Frontend: `frontend/src/components/DocumentUpload.tsx`

---

### 2. LLM-Integration

**MVP-Umfang:**
- OpenAI-API-Unterstützung (GPT-4, GPT-3.5-turbo)
- Ollama-Unterstützung für lokale/Open-Source-Modelle
- Anbieter-Konfiguration über Umgebungsvariablen
- Separater LLM-Anbieter für Chat und Embeddings
- Streaming-Antworten an Frontend
- Eleganter Fallback über Ollama für lokale Entwicklung

**Implementierte Anbieter:**
- OpenAI (erfordert `OPAA_OPENAI_API_KEY`)
- Ollama (kann lokal laufen)
- Ollama-Anbieter (kein API-Schlüssel für lokale Entwicklung benötigt)

**Nicht im MVP (Geplant):**
- Claude / Anthropic API
- Azure OpenAI
- Anthropic/Google Vertex AI
- Modell-spezifische Optimierungen (Function Calling, Vision-Modelle)
- Token-Nutzungs-Tracking und Kostenvorhersage

**Test-Coverage:**
- `ProviderConfigurationTest` — Standard- und Ollama-Konfiguration
- `OpenAiIntegrationTest` — Echte OpenAI-Aufrufe (erfordert API-Schlüssel)
- `MixedProviderConfigurationTest` — Verschiedene Chat-/Embedding-Anbieter
- Frontend-Mock-Tests über MSW

**Wichtige Dateien:**
- Backend: `backend/src/main/java/io/opaa/llm/`
- Konfiguration: `backend/src/main/resources/application*.yml`

---

### 3. Benutzer-Frontends

**MVP-Umfang:**

#### Web-UI
- Chat-Schnittstelle (Fragen stellen, Antworten anzeigen)
- Quelldokument-Angabe (klickbare Links)
- Dokument-Browser (suchen, Vorschau)
- Persönlicher Workspace für Uploads
- Gesprächshistorie
- Feedback-Buttons (Daumen hoch/runter für Antworten)
- Benutzer-Authentifizierung (Mock + Echt)
- Einstellungsseite (API-Token-Verwaltung)

#### REST-API
- `/api/v1/query` — Fragen stellen
- `/api/v1/indexing/trigger` — Indizierungsjob starten
- `/api/v1/indexing/status` — Indizierungsstatus abrufen
- `/api/v1/documents/upload` — Dokumente hochladen

**Nicht im MVP (Geplant):**
- Chat-Plattform-Integrationen (Slack, Mattermost, RocketChat)
- IDE-Integrationen (VS Code, IntelliJ)
- CLI-Tool
- Mobile-Apps (iOS/Android)
- Dokument-Export (PDF, Markdown)
- Gesprächs-Teilen mit zeitlich begrenzten Links

**Test-Coverage:**
- Frontend: Komponenten-Tests (Vitest) mit MSW-Mocks
- Backend: Integrationstests für API-Endpunkte
- E2E-Smoke-Tests über Docker Compose

**Wichtige Dateien:**
- Frontend: `frontend/src/pages/Chat.tsx`, `DocumentBrowser.tsx`
- Backend: `backend/src/main/java/io/opaa/api/`

---

### 4. Zugangskontrolle & Workspaces

**MVP-Umfang:**
- Einzelner Workspace pro Deployment
- Benutzerrolle: Owner/User/Viewer (Grundstufen)
- Authentifizierung: Mock-Anbieter + SSO-bereite Architektur
- Dokument-Zugangskontrolle (Berechtigungen von Quelle erben)
- Grundlegende Autorisierungs-Checks auf API-Endpunkten

**Nicht im MVP (Geplant):**
- Multi-Workspace-Unterstützung pro Benutzer
- Rollenbasierte Zugangskontrolle (RBAC) mit benutzerdefinierten Rollen
- Attribut-basierte Zugangskontrolle (ABAC)
- Feinkörnige Berechtigungen auf Dokumentenebene
- Audit-Logging (wer hat was wann abgerufen)
- SSO-Integrationen (OAuth2, SAML)

**Test-Coverage:**
- Autorisierungs-Checks in Integrationstests
- Frontend berechtigungsbasiertes UI-Ausblenden (Komponenten)

**Wichtige Dateien:**
- Backend: `backend/src/main/java/io/opaa/access/`
- Frontend: `frontend/src/utils/permissions.ts`

---

### 5. Deployment & Infrastruktur

**MVP-Umfang:**
- Docker-Compose-Setup (Backend, Frontend, PostgreSQL)
- PostgreSQL 18 mit pgvector für Embeddings
- Liquibase für Datenbankmigrationen
- Health-Check-Endpunkte
- Umgebungsvariablen-Konfiguration
- CI/CD-Pipeline (GitHub Actions)

**Enthalten:**
- Backend-Build + Tests (Gradle)
- Frontend-Build + Lint + Tests (npm/Vite)
- Spotless-Code-Formatierungs-Checks
- Docker-Image-Builds

**Nicht im MVP (Geplant):**
- Kubernetes-Deployment-Templates
- Multi-Region-Deployment
- Load Balancing und horizontales Skalieren
- Monitoring-Dashboards (Prometheus, Grafana)
- Log-Aggregation (ELK, Loki)
- Backup- und Disaster-Recovery-Prozeduren

**Test-Coverage:**
- CI-Pipeline verifiziert, dass alle Build-Schritte bestehen
- Docker-Compose-Smoke-Tests verifizieren Integration

**Wichtige Dateien:**
- `docker-compose.yml` — Vollständige Stack-Definition
- `.github/workflows/ci.yml` — CI-Pipeline
- `backend/gradle/libs.versions.toml` — Abhängigkeitsverwaltung

---

## Was durchgehend funktioniert

**Verifizierter Fluss (MVP):**
1. Benutzer lädt Dokumente hoch → gespeichert und indiziert
2. Embeddings über konfigurierten LLM-Anbieter generiert
3. Dokumente gechunked und in PostgreSQL mit pgvector gespeichert
4. Benutzer stellt Frage in Web-UI
5. Frage eingebettet und semantisch gesucht
6. Top-übereinstimmende Dokumente abgerufen
7. LLM generiert Antwort mit Quellenangabe
8. Antwort in Echtzeit an Frontend gestreamt
9. Benutzer sieht Quellen als klickbare Links
10. Benutzer kann indizierte Dokumente durchsuchen
11. Benutzer kann Feedback geben (Daumen hoch/runter)

---

## Nächste Phasen (Post-MVP)

### Phase 1: Konnektor-Ökosystem
- Confluence-Konnektor (Wiki-Seiten, Spaces)
- E-Mail-Konnektor (IMAP, Gmail API, Office 365)
- Jira-Konnektor (Issues, Kommentare)
- GitHub-Konnektor (Issues, Discussions, READMEs)

### Phase 2: Erweiterte LLM-Fähigkeiten
- Anthropic Claude-Unterstützung
- Vision-Modelle (für OCR, Bildverständnis)
- Function Calling für strukturierte Datenextraktion
- Multi-Modell-Inferenz (Ensemble-Methoden)

### Phase 3: Team-Features
- Multi-Workspace-Unterstützung
- Feinkörniges RBAC
- Gesprächs-Teilen
- Audit-Logging

### Phase 4: Skalierung & Betrieb
- Kubernetes-Unterstützung
- Horizontales Skalieren
- Monitoring und Alerting
- Hochverfügbarkeits-Architektur

---

## Verifikations-Checkliste

MVP-Bereitschaft bestätigen:

- [ ] Alle Backend-Tests bestehen: `cd backend && ./gradlew build`
- [ ] Alle Frontend-Tests bestehen: `cd frontend && npm run test -- --run`
- [ ] Docker-Compose-Smoke-Test besteht (siehe `MVP-VERIFICATION.md`)
- [ ] Lokale Entwicklung funktioniert (siehe `AGENTS.md` Abschnitt Build & Test)
- [ ] Dokumentation ist aktuell (diese Datei, Feature-Spezifikationen in `docs/features/`)

---

## Beitragen

Beim Implementieren von Post-MVP-Features:
1. Den relevanten Abschnitt oben referenzieren
2. Dieses Dokument mit abgeschlossenen Features aktualisieren
3. Feature-Spezifikationen in `docs/features/` mit Implementierung abgleichen
4. Sicherstellen, dass alle Tests bestehen, bevor PR eingereicht wird

Weitere Details finden Sie in `AGENTS.md`, `CONTRIBUTING.md` und Architecture Decision Records in `docs/decisions/`.
