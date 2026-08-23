# Issue #479 — feat(library): Upload nur in UPLOAD-Bibliotheken und Löschverhalten für Konnektorbibliotheken
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #503 (2026-08-19)

**Laut Issue:** Upload sollte auf `UPLOAD`-Bibliotheken beschränkt werden (409 sonst), Löschen einer Konnektorbibliothek sollte Dokumente und Chunks mitnehmen, `UPLOAD` behält die bestehende Sperre bei vorhandenem Bestand.

**Geliefert:** Wie gefordert. `POST /api/v1/libraries/{libraryId}/documents` liefert 409 mit deutscher Fehlermeldung für Konnektorbibliotheken. `DELETE /api/v1/libraries/{libraryId}` entfernt bei Konnektorbibliotheken Dokumentzeilen und Vektorspeicher-Chunks mit, inkl. Audit-Eintrag `LIBRARY_DELETED` mit `documentsRemoved`. Reproduktionsnachweis mit drei roten/grünen Testläufen im PR dokumentiert. Frontend blendet den Upload-Bereich für Konnektorbibliotheken aus und warnt bei der Löschbestätigung zusätzlich vor dem Mitnehmen des Bestands.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryDocumentService.java` und `KnowledgeLibraryService.java` existieren mit entsprechender Logik (bestätigt durch begleitende Tests `LibraryDocumentServiceIntegrationTest.java`, `LibraryDocumentServiceTest.java`). Löschverhalten wurde später um eine explizite Sperre bei laufenden Jobs ergänzt (`KnowledgeLibraryServiceDeleteLockTest.java`, sichtbar in #485-Dateiliste) — Weiterentwicklung, keine Rücknahme.

**Themen:** backend, spaces, retrieval, adr
