# Issue #892 — refactor(audit): AuditEvent-Builder und Domain-Events — Doppelbuchführung strukturell absichern
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #895 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 3, letzter Baustein. Zwei Probleme: `AuditEventRecorder` hatte 10–13 Positionsparameter (Vertauschungsgefahr), und jeder Grant-/Bibliotheks-Schreibpfad musste Audit UND PermissionHistoryService von Hand parallel aufrufen — das Vergessen einer Seite war genau die Lücke, die #545 nachträglich schließen musste. Gefordert: ein Builder für `AuditEvent` sowie synchrone Domain-Events (`GrantChanged`/`LibraryChanged`) mit Audit- und History-Listenern, Scope beschränkt auf die Grant-/Library-Pfade.

**Geliefert:** Der Builder kam als eigener, vorgelagerter PR #893 (nicht im Chunk enthalten, hier nur referenziert). Dieser PR (#895) liefert die Domain-Events: `GrantChanged`/`LibraryChanged` mit `AuditListener`/`PermissionHistoryListener` (package-private, `io.opaa.library`), beide normale `@EventListener` (nicht `@TransactionalEventListener`) — Rollback der Transaktion rollt beide Seiten mit zurück wie vorher. Bewusst nicht umgestellt: `DENIED`-Eskalationswache, `LIBRARY_CHANGED`/`LIBRARY_SOURCE_UPDATED` (keine History-Gegenseite), `deleteLibrary` (variable Intervallanzahl). Bemerkenswert: Dieser PR ersetzt den ursprünglichen #894, der beim Löschen seines Basis-Branches nach einem gestapelten Merge automatisch von GitHub geschlossen wurde — derselbe Diff, per Cherry-Pick übertragen.

**Verifikation:** `backend/src/main/java/io/opaa/library/GrantChanged.java` existiert im Worktree.

**Themen:** audit, refactoring, permissions, epic-826, domain-events
