# Issue #822 — feat(frontend): Ordner-Navigation in der Bibliotheksansicht
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #830 (2026-08-24)

**Laut Issue:** Teil von Epic #520 (Phase 3). Ordner-Navigation in `LibraryDetailPage.tsx`: Breadcrumb, Ordnerzeilen, URL-State (`?folder=…`), Ordner anlegen/umbenennen/löschen (nur UPLOAD-Bibliotheken, ab EDITOR) mit Bestätigungsdialog samt Dokumentanzahl, Upload in den geöffneten Ordner, Suche zeigt Ordnerpfad.

**Geliefert:** Wie gefordert, plus zusätzliche Robustheit: eine ungültige/fremde `folderId` (404) fängt der `documentStore` ab und fällt sauber auf die Wurzel zurück; Namenskonflikte (409) erscheinen als Meldung im jeweiligen Dialog statt ihn zu schließen. Alle Texte deutsch inkl. `aria-label`.

**Verifikation:** `frontend/src/pages/LibraryDetailPage.tsx`, `frontend/src/stores/documentStore.ts` existieren im Worktree und enthalten Ordner-bezogene Logik (Folder-CRUD-Aktionen, `folderId`-Parameter).

**Themen:** frontend, library, ordner, ui
