# Issue #434 — feat(upload): Rate-Limit und/oder asynchrone Verarbeitung für den Dokument-Upload-Endpunkt
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #589 (2026-08-20)

**Laut Issue:** Der Upload-Endpunkt verarbeitet Parsen/Embedding synchron im Request-Thread und ist von keinem Rate-Limit erfasst — ein EDITOR könnte damit unverhältnismäßig viele Threads/Verbindungen belegen. Zur Wahl standen ein Rate-Limit-Präfixeintrag und/oder asynchrone Verarbeitung.

**Geliefert:** Maintainer-Entscheidung für die asynchrone Variante statt eines zusätzlichen Rate-Limits. Der Endpunkt validiert weiterhin synchron (Format, Größe, Dedup), legt die Dokumentzeile sofort mit Status `PENDING` an und verarbeitet Parsen/Embedding danach asynchron über die bestehende Executor-Infrastruktur (`FileProcessingService#processUploadedFileAsync`). Ein Fehler landet als `FAILED` mit neuer Spalte `documents.error_message` statt einer synchronen 4xx-Antwort; `EmptyDocumentContentException` entfällt dadurch. Migration 036 ergänzt die Spalte.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/FileProcessingService.java` existiert im heutigen Code.

**Themen:** upload, backend, performance, indexing
