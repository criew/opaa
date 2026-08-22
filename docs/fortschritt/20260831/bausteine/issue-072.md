# Issue #72 — 🔵 [LOW] Magic Numbers Without Documentation
- Geschlossen: 2026-03-02 (completed)
- Labels: enhancement, backend, size:S
- PRs: #93 (2026-03-02)

**Laut Issue:** Magic Numbers (`DEFAULT_TOP_K`, `DEFAULT_SIMILARITY_THRESHOLD`, Chunk-/Batch-Größen) ohne Begründung im Code bzw. in `application.yml`. Gefordert: Javadoc mit Rationale, README-Dokumentation, ggf. Konfigurierbarkeit und Validierung.

**Geliefert:** PR #93 dokumentiert die Magic Numbers per Javadoc über mehrere Klassen (QueryService, QueryConfiguration, ChunkingService, IndexingProperties, RateLimitService, RateLimitProperties), macht `top-k` und `similarity-threshold` über eine neue `QueryProperties`-Record-Klasse konfigurierbar, ergänzt Validierungsgrenzen und erweitert `docs/deployment.md` um eine vollständige Umgebungsvariablen-Referenztabelle. Deckt die Forderung vollständig ab.

**Verifikation:** `backend/src/main/java/io/opaa/query/QueryProperties.java` existiert im heutigen Worktree weiterhin.

**Themen:** doku, backend, konfiguration
