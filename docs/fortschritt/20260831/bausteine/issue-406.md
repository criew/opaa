# Issue #406 — fix(query): Über die Indexierung eingespielte Dokumente sind im Chat für niemanden auffindbar
- Geschlossen: 2026-08-15 (completed)
- Labels: bug, backend, size:M
- PRs: #413 (2026-08-15)

**Laut Issue:** Issue-Body ist nur „@-" (leer/Platzhalter). Titel beschreibt einen Bug: über Indizierung eingespielte Dokumente waren im Chat für niemanden auffindbar.

**Geliefert:** Im Chunk-Datensatz kein PR verknüpft, `gh issue view --comments` liefert keine Kommentare. Der Branchname `feature/406_systembibliothek-rechtepfade` und die exakte zeitliche Übereinstimmung (PR #413 gemerged 2026-08-15T10:33:04Z, Issue geschlossen 2026-08-15T10:33:05Z) belegen: PR #413 „fix(library): Rechteprüfung für die System-Bibliothek vereinheitlichen" ist die schließende Änderung. Inhaltlich passt das zusammen — eine uneinheitliche Rechteprüfung an der System-Bibliothek würde erklären, warum indizierte Dokumente für niemanden (auch nicht Berechtigte) auffindbar waren. Geändert: `LibraryAccessService.java`, `KnowledgeLibraryService.java`, `LibraryOwnerType.java` sowie Tests in `KnowledgeLibraryServiceIntegrationTest`, `LibraryAccessServiceTest`, `QueryIntegrationTest`; dazu `docs/features/spaces-and-assets.md` und `docs/migrations/012-knowledge-library.md`. PR-Body ebenfalls nur „@-", daher keine Aussage zum Reproduktionsnachweis möglich.

**Verifikation:** `LibraryAccessService.java` existiert im Worktree unter `backend/src/main/java/io/opaa/library/`.

**Themen:** query, retrieval, rechte, bugfix, backend, doku-lücke
