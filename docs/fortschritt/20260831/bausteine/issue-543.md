# Issue #543 — Space mit fremden privaten Chats ist dauerhaft unlöschbar
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #613 (2026-08-20)

**Laut Issue:** Mit #525 schützt `fk_chats_space` (RESTRICT) private Chats vor fremder Löschung — ein Space mit Chats lässt sich nicht löschen (409). Das erzeugt ein Betriebsloch: Der Space-Eigentümer sieht fremde private Chats nicht und kann sie nicht entfernen, ein Space mit irgendeinem Chat ist damit nie wieder löschbar. Im Ticket wurden drei Lösungsoptionen zur Entscheidung gestellt: Archivieren statt Löschen, private Chats beim Löschen in den Default-Space des Autors verschieben, oder Löschung mit Frist + Benachrichtigung.

**Geliefert:** Maintainer-Entscheidung fiel auf „Archivieren statt Löschen". Neuer Endpunkt `POST /api/v1/spaces/{spaceId}/archive` (Owner/System-Admin, idempotent, blockiert für den Standard-Space); `archived`-Feld an `SpaceResponse`/`SpaceListResponse`; Migrationen 037 (Spalte) und 038 (Audit-Enum `SPACE_ARCHIVED`). Ein archivierter Space nimmt keinen neuen Chat mehr an (409 bei `createChat`), wird aus `listSpaces` ausgeblendet außer für Mitglieder mit eigenem Chat darin, und bestehende Chats bleiben für ihren Autor lesbar. Echtes Löschen bleibt möglich, sobald kein Chat mehr im Space liegt. Frontend erhält eine „Space archivieren"-Aktion und eine „Archiviert"-Kennzeichnung. Spezifikation entsprechend ergänzt.

**Verifikation:** Migrationsdatei `backend/src/main/resources/db/changelog/changes/039-add-archived-to-spaces.yaml` existiert im Worktree; `SpaceService.java` enthält 17 Treffer für „archive". Umsetzung bestätigt vorhanden.

**Themen:** spaces, workspace, chats, betrieb, löschsperre
