# Issue #53 — feat(indexing): skip unchanged documents using SHA-256 checksum
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, backend
- PRs: #57 (2026-02-27)

**Laut Issue:** Bei jedem Indexing-Trigger wurden alle Dokumente erneut verarbeitet, auch unveränderte — teuer wegen Parsing/Chunking/Embedding-API-Calls. Gefordert war eine SHA-256-Checksumme pro Dokument (neue Spalte `checksum` auf `documents`), Vergleich vor dem Parsing, Überspringen bei Übereinstimmung und Status `INDEXED`, separates Zählen übersprungener Dokumente (`documents_skipped`) inkl. API-/UI-Anzeige.

**Geliefert:** PR #57 setzt den Vorschlag praktisch vollständig um: neue `ChecksumService` (SHA-256 via `DigestInputStream`), `FileProcessingResult`-Enum (`PROCESSED`/`SKIPPED`), `checksum`-Spalte auf `Document`, `documents_skipped`-Zähler auf `IndexingJob`, Liquibase-Migration `005-add-checksum-and-skipped-columns.yaml`, Frontend-Anzeige "X processed (Y skipped)". Keine wesentlichen Abweichungen vom Issue-Vorschlag.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/ChecksumService.java`, `Document.java`, `FileProcessingResult.java` und `FileProcessingService.java` existieren weiterhin im heutigen Code.

**Themen:** backend, indexing, checksum, performance
