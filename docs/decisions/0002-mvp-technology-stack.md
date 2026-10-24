# ADR-0002: Technologie-Stack

## Status

Akzeptiert · zuletzt geändert 2026-10-20 · ergänzt durch ADR-0005, ADR-0014, ADR-0025, ADR-0033, ADR-0034, ADR-0042, ADR-0053

## Kontext

OPAA brauchte für das MVP (Frage-Antwort über indizierte Dokumente, Web-Frontend, LLM-Anbindung)
einen Stack mit Enterprise-Tauglichkeit, starker KI-Ökosystem-Unterstützung (LLM, Embeddings,
Vektorsuche), sauberer Trennung von Frontend und Backend über eine REST-API, einfacher lokaler
Entwicklung, Container-Deployment und ohne Vendor-Lock-in beim LLM-Anbieter. Der Umsetzungsstand
steht im [Gesamtstand](../fortschritt/gesamtstand.md), das Zielbild in [VISION.md](../VISION.md).

## Entscheidung

### 1. Backend: Java 21, Spring Boot 4.1, Spring AI 2.0, Gradle 9.8

Spring AI 2.0 setzt Spring Boot 4 voraus, beide Versionen werden gemeinsam angehoben. Modularer
Monolith unter `io.opaa`, aufgeteilt in logische Module mit geprüften Grenzen
(`backend/AGENTS.md`, je Modul eine `AGENTS.md` im Hauptpaket). Versionen stehen in
`backend/gradle/libs.versions.toml` und `backend/gradle/wrapper/gradle-wrapper.properties`. Das
Backend läuft als eine oder mehrere gleichrangige Instanzen gegen eine Datenbank (ADR-0053).

### 2. Frontend: React 19, TypeScript 6, Material UI 9, Vite 8

React Router 8 (`react-router`), Zustand, Axios; Tests mit Vitest 5 und React Testing Library,
Mocks mit MSW 3; Paketmanager pnpm (Version im `packageManager`-Feld der `frontend/package.json`),
Node-Version nach `engines` der `frontend/package.json`. Das Frontend spricht ausschließlich die REST-API und ist damit einer
von mehreren möglichen Clients. Material UI ab v9 kennt keine System-Props auf `Typography`/`Stack`;
Layoutwerte gehören in `sx`.

### 3. Datenbank: PostgreSQL 18 mit pgvector

Eine Datenbank für relationale Daten und Vektoren; pgvector 0.8 oder neuer, der Start bricht ohne
ab (`PgVectorVersionGuard`, `DatabasePrerequisiteCheck`). Liquibase verwaltet das Anwendungsschema
nach ADR-0034; die Tabelle `vector_store` legt Spring AI über `initialize-schema: true` an
(HNSW-Index, Vorgabe Cosinus-Distanz). Schreibzugriff läuft über Spring AIs `PgVectorStore`
(`indexing.chunk.VectorChunkStore`), die Suche über eigenes SQL mit `JdbcTemplate`
(`retrieval.search.VectorChunkSearch`, iterativer HNSW-Scan innerhalb des Rechtefilters). pgvector
ist der einzige unterstützte Vektorspeicher (ADR-0014).

### 4. Dokumentformate: eigenes Modul `io.opaa.format`

Je Format ein Reader mit eigener Bibliothek (PDFBox, POI, Tika, mime4j, commons-csv) und eigenem
Schnitt; Zulassung über `SupportedDocumentFormats`, Routing nach erkanntem Inhalt über
`DocumentFormatRegistry`, Tika nur als Rückfall (`format.file.fallback.TikaFallbackFormat`). Ein
neues Format ist eine Klasse plus eine Bean in `format.config.FormatConfiguration`.

### 5. LLM-Anbindung: ausschließlich das OpenAI-kompatible Protokoll

`spring.ai.model.chat` und `spring.ai.model.embedding` stehen fest auf `openai`; die Basis-URL zeigt
standardmäßig auf ein lokales Ollama (`http://localhost:11434/v1`, im Compose-Stack der Dienst
`ollama`). Chat- und Embedding-Endpunkt sind getrennt konfigurierbar (`OPAA_OPENAI_CHAT_*`,
`OPAA_OPENAI_EMBEDDING_*`), Standard-Embedding ist `bge-m3`. Ein fremder Anbieter wird nur durch
ausdrückliches Überschreiben erreicht; nichts zeigt stillschweigend nach außen.

### 6. Authentifizierung

OIDC-Resource-Server mit Keycloak als Referenz, dazu Dev-Modus (ADR-0005), mehrere Anbieter
(ADR-0025) und lokale Konten (ADR-0033).

### 7. Deployment: Docker Compose und Helm

`docker-compose.yml` liefert Postgres, Backend und Frontend, dazu Compose-Profile für Keycloak,
Ollama, Objektspeicher, Mailpit und die Demo. Kubernetes wird über das Helm-Chart unter
`deploy/helm/opaa/` beliefert (ADR-0042). Lokale Entwicklung ohne Docker: `./gradlew bootRun` mit
Auth-Profil, `pnpm run dev`, lokales PostgreSQL.

### 8. Testing: Testcontainers und GitHub Actions

Backend-Integrationstests laufen gegen ein pgvector-Image per Testcontainers; die CI
(`.github/workflows/ci.yml`) prüft Backend und Frontend bei jedem Push und PR, teure Suiten laufen
nächtlich oder per Label (siehe [docs/entwicklung/build-und-ci.md](../entwicklung/build-und-ci.md)).

## Konsequenzen

Einfacher: Spring AI liefert die Abstraktionen für Chat, Embedding und Vektorspeicher; Java/Spring
senkt die Hürde in Enterprise-Umgebungen; ein Wechsel zwischen lokalen und gehosteten Modellen ist
Konfiguration; Testcontainers stellt echtes pgvector lokal und in der CI bereit; MSW erlaubt
Frontend-Arbeit ohne Backend.

Schwieriger: Pythons KI-Ökosystem ist breiter; zwei Sprachen verlangen breitere Fähigkeiten; pgvector
stößt bei sehr großen Beständen an Grenzen, ein Wechsel ist nicht vorgesehen.

Restrisiken: Spring AI ist jünger als die Python-Alternativen; Hauptversionswechsel (Spring Boot,
Material UI) ziehen Migrationen nach sich.

## Verworfene Alternativen

- Python-Backend — schwächere Enterprise-Akzeptanz und Wartbarkeit trotz breiterem KI-Ökosystem.
- Separate Vektor-Datenbank (Milvus, Qdrant) — eine zweite Datenbank im Betrieb ohne Bedarf.
- Native Anbieter-Starter je LLM-Anbieter — ein Protokoll genügt für Cloud und lokale Modelle.

## Offen

Nichts.
