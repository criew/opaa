# Issue #419 — feat(indexing): Indizierungsläufe zielen auf eine wählbare Wissensbibliothek statt auf die System-Bibliothek
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, frontend, size:M, workspace
- PRs: #431 (2026-08-17)

**Laut Issue:** `FileProcessingService` schrieb hart `SYSTEM_LIBRARY_ID` (Verzeichnis- und URL-Indizierung), die System-Bibliothek ist `PRIVATE` ohne Grants — jedes so indizierte Dokument war für Normalnutzer unauffindbar (Zwischenzustand aus Epic #198). Gefordert: `libraryId` als Pflichtfeld im Trigger-Request, `EDITOR`-Mindestrecht auf der Zielbibliothek, Durchreichen der Zielbibliothek durch die gesamte Indizierungskette, Frontend-Auswahl in `AdminDrawer`, `docs/STATUS.md` aktualisieren.

**Geliefert:** PR #431 setzt den Umfang um, inklusive Migration 019 für `IndexingJob.libraryId`. Der PR-Body dokumentiert eine zweite Review-Runde mit drei blockierenden Befunden, die vor Merge behoben wurden — darunter ein wesentlicher: die `EDITOR`-Prüfung war am einzigen erreichbaren Endpunkt (`/trigger`, `@PreAuthorize SYSTEM_ADMIN`) wirkungslos, weil `effectiveRole` für System-Admins bedingungslos `OWNER` zurückgab und der 403-Zweig damit nie erreicht wurde — behoben durch `systemAdmin=false` bei der `canEdit`-Prüfung, mit dokumentierter Ausnahme für die System-Bibliothek selbst. Ein Follow-up-Issue #433 (gelöschte Zielbibliothek mitten im Lauf) wurde bewusst ausgelagert. `NoHardcodedSystemLibraryAssignmentTest` sichert das Abnahmekriterium „kein Produktionscode weist mehr SYSTEM_LIBRARY_ID zu" testbasiert ab, mit dokumentiertem Rot/Grün-Nachweis.

**Verifikation:** Nicht vertieft geprüft; die im Datensatz mitgelieferte Dateiliste (u. a. `IndexingController.java`, `AsyncIndexingExecutor.java`, `UrlIndexingExecutor.java`, `NoHardcodedSystemLibraryAssignmentTest.java`, `Migration019IndexingJobLibraryTest.java`, `AdminDrawer.tsx`) deckt sich mit dem beschriebenen Umfang.

**Themen:** indexing, spaces, workspace, rechteformel, epic-198
