# Issue #736 — feat(api): Download-Endpunkt für Originaldokumente
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M
- PRs: #742 (2026-08-22)

**Laut Issue:** Maintainer-Feedback aus dem Klick-Test der Demo: Originaldokumente sollen aus Suchergebnissen/Bibliotheken abrufbar sein. Gefordert: `GET /api/v1/documents/{documentId}/content` (spec-first), Streaming mit korrektem Content-Type/`Content-Disposition: inline`, Zugriffsprüfung mind. VIEWER über `LibraryAccessService`, 404 bei fremden Dokumenten (kein Existenz-Leak), nur `UPLOAD`/`FILESYSTEM` liefern Inhalte, Path-Traversal-Schutz, Integrationstests, Doku-Update.

**Geliefert:** Deckungsgleich laut PR-Beschreibung — neuer `DocumentController` (eigener Pfad statt `LibraryController`, da Präfix-Kollision), `LibraryDocumentService#loadContent` mit Rollen-, Quellentyp- und Traversal-Prüfung, einheitliches 404 für alle Fehlerfälle. Integrationstests für Erfolgsfall, fehlende Berechtigung, Remote-Quelle, fehlende Datei, Traversal-Versuch.

**Verifikation:** Der Worktree-Branch (`feature/744_leistungsinventur`) wurde vor dem Merge dieses PRs erstellt (letzter enthaltener Commit: #735 vom 22.08. früh) — `backend/src/main/java/io/opaa/api/DocumentController.java` ist im Worktree **nicht** vorhanden. Das ist ein Zeitfenster-Effekt des Worktrees, keine Auffälligkeit am Code selbst; laut `git -C main log` ist der Merge-Commit `2afa1e1` auf `main` vorhanden.

**Themen:** api, dokumente, download, backend, deeplinks
