# Issue #632 — fix(indexing): Konnektorpfade re-inserten gelöschte Dokumentzeilen (save statt bedingter Aktualisierung)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #633 (2026-08-20)

**Laut Issue:** Die Konnektorpfade in `FileProcessingService` schrieben Statusübergänge per `documentRepository.save(doc)`. Wurde ein Konnektor-Dokument während der Verarbeitung gelöscht, re-insertete `save` die gelöschte Zeile als Zombie (Id wird im Konstruktor vergeben, kein `@Version`). Der Upload-Pfad war mit PR #589 bereits auf bedingte `@Modifying`-UPDATEs umgestellt; dasselbe Muster fehlte für Konnektorpfade. Gefordert: Statusübergänge als bedingte UPDATEs, bei 0 betroffenen Zeilen eigene Chunks nachräumen, Test für das Löschfenster, Reproduktionsnachweis.

**Geliefert:** Die drei Konnektor-Schreibpfade (FILESYSTEM, URL/HTTP, RSS) in `FileProcessingService` laufen jetzt über bedingte `@Modifying`-UPDATEs (`DocumentRepository#markIndexedFromSource`/`#markFailed`), verallgemeinert aus dem #589-Muster. Bei 0 betroffenen Zeilen räumt der Aufrufer die eigenen Vector-Chunks per `vectorStore.delete(...)` auf und kehrt still mit `SKIPPED` zurück. Reproduktionsnachweis per Integrationstest mit Testcontainers-Postgres erbracht (rot: `expected SKIPPED but was PROCESSED`, grün nach Fix). Im selben PR wurde zusätzlich #636 (Nachbesserung aus dem Review) mitgeliefert — drei verwandte Restfenster (Löschreihenfolge in `deleteLibrary`, Chunk-Aufräumen bei Exception-Pfaden, `failAlreadyPersistedUpload`).

**Verifikation:** `FileProcessingService.java` enthält `markConnectorFailedAfterException` und ruft `documentRepository.markIndexedFromSource(...)` auf (Zeilen 130, 249, 333, 441ff, 457, 510) — Umsetzung im Code vorhanden.

**Themen:** backend, indexing, retrieval, spaces, race-condition, tests
