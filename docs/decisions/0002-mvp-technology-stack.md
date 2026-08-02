# ADR-0002: MVP-Technologie-Stack

## Status

Akzeptiert

## Kontext

OPAA benötigt einen Technologie-Stack, um das in [docs/MVP.md](../MVP.md) definierte MVP zu implementieren. Das MVP umfasst ein Frage-Antwort-System mit Dokument-Indizierung (RAG), ein Web-Frontend und eine LLM-Integrationsschicht. Kernanforderungen sind:

- Enterprise-Tauglichkeit und langfristige Wartbarkeit
- Starke KI/ML-Ökosystem-Unterstützung (LLM, Embeddings, Vektorsuche)
- Saubere Trennung von Frontend und Backend über REST-API
- Einfache lokale Entwicklung und Docker-basiertes Deployment
- Kein Vendor-Lock-in für LLM-Anbieter

## Entscheidung

### Backend: Java 21 mit Spring Boot 4.x + Spring AI 2.0.0

- **Spring Boot 4.x** bietet ein ausgereiftes, enterprise-taugliches Framework mit umfangreicher Ökosystem-Unterstützung. Der MVP startete auf Spring Boot 3.5.x; die Migration auf 4.1 (Spring Framework 7, Jakarta EE 11, Jackson 3) erfolgte mit Issue #188.
- **Spring AI 2.0.0** bietet eingebaute Abstraktionen für LLM-Clients (OpenAI-kompatibel), Embedding-Modelle, Vektorspeicher (einschließlich pgvector) und Dokument-Reader (einschließlich Apache Tika). Spring AI 2.0 setzt Spring Boot 4 voraus, beide Bumps gehören daher zusammen.
- **Gradle 9.6.1** (Kotlin DSL) wird als Build-System verwendet und bietet schnelle inkrementelle Builds und eine prägnante Build-Konfiguration.
- Das Backend ist als **modularer Monolith** mit separaten Packages unter `io.opaa` für `indexing`, `query` und `api` strukturiert, was eine spätere Zerlegung in Microservices ermöglicht.

### Frontend: React + TypeScript + Material UI 9.2.0

- **React** mit **TypeScript** ist der Industriestandard für moderne Web-Anwendungen. Der MVP startete auf TypeScript 5.9; die Anhebung auf TypeScript 6.0 erfolgte mit Issue #189.
- **Material UI 9.2.0** bietet eine umfassende, zugängliche Komponentenbibliothek mit konsistentem Design. Der MVP startete auf Material UI 7.3.8; die Migration auf 9.2 erfolgte mit Issue #189 (v8 wurde vom MUI-Team zugunsten der MUI-X-Angleichung übersprungen). Seit v9 entfallen die System-Props auf `Typography`/`Stack` — Layout-Werte gehören in die `sx`-Prop.
- **React Router 8** wird über das Paket `react-router` eingebunden; `react-router-dom` wurde in v8 entfernt.
- **Vite 8** (Rolldown-Backend) dient als Build-Tool und Dev-Server. Das Frontend benötigt seit Issue #189 **Node 22+** (genauer: `^22.22.2 || ^24.15.0 || >=26.0.0`, die strengste transitive Anforderung stammt von `jsdom`).
- **Vitest + React Testing Library** wird für Frontend-Unit-Tests verwendet und bietet schnelle Vite-native Testausführung mit einer Jest-kompatiblen API.
- **MSW (Mock Service Worker)** ermöglicht Frontend-Entwicklung und -Tests ohne laufendes Backend durch Abfangen von HTTP-Anfragen.
- Das Frontend kommuniziert ausschließlich über die REST-API des Backends, wodurch es einer von vielen möglichen Clients ist.

### Datenbank: PostgreSQL 18 + pgvector

- **PostgreSQL 18** dient als einzelne Datenbank sowohl für relationale Daten als auch für Vektorspeicherung.
- **pgvector** fügt Vektorsimilaritäts-Suchfähigkeiten hinzu, ohne eine separate Vektor-Datenbank zu benötigen.
- **Liquibase** verwaltet anwendungsspezifische Schema-Migrationen (`documents`, `indexing_jobs`) und bietet XML/YAML-basierte Changesets mit Rollback-Unterstützung. Die `vector_store`-Tabelle wird von Spring AI über `initialize-schema: true` verwaltet.
- Dies reduziert die Betriebskomplexität (eine zu verwaltende Datenbank) und bietet gleichzeitig ausreichende Leistung für MVP-Maßstab.

### Dokument-Parsing: Apache Tika über Spring AI

- **Apache Tika** unterstützt alle gängigen Dokumentformate (Markdown, Klartext, PDF, Word, PowerPoint) über eine einzelne Integration.
- Spring AIs `TikaDocumentReader` bietet nahtlose Integration.
- Das Hinzufügen neuer Dokumentformate erfordert keine Code-Änderungen.

### LLM-Schnittstelle: OpenAI-kompatible API

- Aller LLM- und Embedding-Zugang geht über den **OpenAI-kompatiblen API**-Standard.
- Dies unterstützt sowohl Cloud-Anbieter (OpenAI) als auch lokale Modelle (Ollama) über dieselbe Schnittstelle.
- LLM und Embedding-Modell sind **unabhängig konfiguriert**, was gemischte Setups ermöglicht (z. B. lokale Embeddings + Cloud-LLM).

### Deployment: Docker Compose + Lokale Entwicklung

- **Docker Compose** mit drei Containern (Frontend, Backend, PostgreSQL) bietet ein Einbefehl-Deployment (`docker compose up`).
- Lokale Entwicklung wird ohne Docker unterstützt (`./gradlew bootRun` + `npm run dev` + lokales PostgreSQL).

### Testing: Testcontainers + GitHub Actions

- **Testcontainers** bietet wegwerfbare PostgreSQL-+pgvector-Instanzen für Backend-Integrationstests.
- **GitHub Actions** führt die CI-Pipeline (Backend-Build/-Test + Frontend-Lint/-Test/-Build) bei jedem Push und PR aus.

## Konsequenzen

### Was einfacher wird

- **KI-Integration**: Spring AI bietet fertige Abstraktionen für LLM, Embeddings, Vektorspeicher und Dokument-Parsing — reduziert Boilerplate erheblich.
- **Enterprise-Akzeptanz**: Java/Spring Boot ist in Enterprise-Umgebungen weit verbreitet, was die Hürde für Beiträge und Deployments senkt.
- **Deployment-Einfachheit**: Docker Compose macht es trivial, den vollständigen Stack lokal oder in Demos auszuführen.
- **LLM-Flexibilität**: OpenAI-kompatible Schnittstelle bedeutet, dass der Wechsel zwischen Cloud- und lokalen Modellen nur Konfigurationsänderungen erfordert.
- **Vektorspeicher-Portabilität**: Spring AIs `VectorStore`-Abstraktion ermöglicht den Wechsel des Vektor-Datenbank-Backends (pgvector, Milvus, Qdrant, usw.) ausschließlich durch Konfiguration — keine Code-Änderungen erforderlich.
- **Dokumentformat-Unterstützung**: Apache Tika behandelt Formatvielfalt ohne per-Format-Implementierungsaufwand.
- **Parallele Entwicklung**: MSW ermöglicht Frontend- und Backend-Entwicklung unabhängig voneinander gegen einen gemeinsamen API-Vertrag.
- **Zuverlässiges Testing**: Testcontainers stellt sicher, dass Integrationstests gegen echtes PostgreSQL + pgvector laufen, sowohl lokal als auch in CI.

### Was schwieriger wird

- **KI-Ökosystem-Breite**: Python hat ein breiteres KI/ML-Ökosystem (LangChain, LlamaIndex, HuggingFace). Einige Spitzenbibliotheken sind möglicherweise noch nicht in Java verfügbar. Spring AI mildert dies, ist aber neuer als Python-Alternativen.
- **Frontend-Backend-Sprachteilung**: Zwei Sprachen (Java + TypeScript) erfordern breitere Fähigkeiten von Beitragenden. Dies wird durch saubere API-Trennung gemildert.
- **pgvector-Skalierungsgrenzen**: Für sehr große Dokumentensammlungen (Millionen von Vektoren) kann schließlich eine dedizierte Vektor-Datenbank (Milvus, Qdrant) benötigt werden. pgvector ist für MVP und mittlere Deployments ausreichend.
