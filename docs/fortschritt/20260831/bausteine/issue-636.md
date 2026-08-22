# Issue #636 — fix(library): Verbleibende Chunk-/Zeilen-Restfenster nach #631/#633 schließen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #633 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #633 (#632) drei vorbestehende Restfenster derselben Zombie-Klasse: (1) `KnowledgeLibraryService#deleteLibrary` löschte Vector-Chunks vor den Dokumentzeilen (umgekehrte Reihenfolge zu #631) — Waisen-Chunks möglich; (2) Konnektor-Fehlerpfade in `FileProcessingService` räumten nach `storeChunks` geworfene Exceptions nicht ab, im Gegensatz zum Upload-Pfad; (3) `LibraryDocumentService#failAlreadyPersistedUpload` speicherte per `save` auf einer bereits committeten Zeile — dieselbe Zombie-Klasse wie #632, schmaleres Fenster.

**Geliefert:** Alle drei Punkte wurden im selben PR #633 wie #632 mitgeliefert (kein eigener PR): Löschreihenfolge in `deleteLibrary` umgedreht (Zeilen zuerst, dann Chunks); neue Methode `markConnectorFailedAfterException` räumt jetzt unbedingt per `vectorStore.delete` auf, bevor sie `markFailed` aufruft; `failAlreadyPersistedUpload` auf bedingte `markFailed`-UPDATE umgestellt. Vier neue Unit-Tests belegen alle drei Punkte mit Rot/Grün-Nachweis (`WantedButNotInvoked`, `VerificationInOrderFailure`, `TooManyActualInvocations` vor dem Fix, grün danach).

**Verifikation:** `FileProcessingService.java` enthält `markConnectorFailedAfterException` (Zeile 510) und referenziert sie an den Exception-Pfaden (130, 249, 333) — Umsetzung vorhanden. Zugehörige Testklassen (`KnowledgeLibraryServiceConnectorDeleteOrderTest`, `LibraryDocumentServiceTest`) sind laut PR-Dateiliste vorhanden.

**Themen:** backend, indexing, retrieval, spaces, race-condition, tests
