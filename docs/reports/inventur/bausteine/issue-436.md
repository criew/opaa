# Issue #436 — fix(library): 403-vs-404-Unterscheidung bei fehlendem Zugriff auf Bestands-Endpunkten vereinheitlichen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #608 (2026-08-20)

**Laut Issue:** Die Upload-Endpunkte (#420) unterscheiden bereits 404 (kein Zugriff) von 403 (zu wenig Zugriff), die restlichen Bibliotheks-Endpunkte (`getLibrary`, `listDocuments`, `updateLibrary`, `deleteLibrary`, Grants) liefern einheitlich 403 und verraten damit die Existenz einer Bibliothek gegenüber Nutzern ohne jeden Zugriff.

**Geliefert:** PR #608 vereinheitlicht dies über einen neuen gemeinsamen Baustein `LibraryAccessService#requireRole(library, userId, systemAdmin, required)` — 404 bei fehlender Rolle, sonst 403. Angewendet auf `getLibrary`, `updateLibrary`, `deleteLibrary`, `listDocuments`, `AssetGrantService#requireManageable` und `LibraryDocumentService#requireEditable` (dort jetzt Delegation statt Duplikat). Reproduktionsnachweis erbracht; bestehende Tests, die 403 für „kein Zugriff" erwarteten, wurden auf 404 korrigiert. Umsetzung entspricht vollständig dem im Issue skizzierten Vorschlag.

**Verifikation:** `backend/src/main/java/io/opaa/library/LibraryAccessService.java` existiert im heutigen Code.

**Themen:** auth, backend, spaces, existenzverschleierung, api-konsistenz
