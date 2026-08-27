# Issue #430 — Rechtehistorie: Verzeichnislauf-Eintrag mit konkretem Sync-Lauf korrelieren
- Geschlossen: 2026-08-24 (not planned)
- Labels: backend, security
- PRs: keine

**Laut Issue:** #238 historisiert Gruppenmitgliedschaften, die ein Verzeichnislauf ändert, mit der Ursache `DIRECTORY_SYNC_ADDED`/`DIRECTORY_SYNC_REMOVED`, lässt sich aber nicht auf den konkreten Synchronisationslauf zurückführen — `DirectorySyncStatus` hält nur den jeweils letzten Lauf je Organisation, nicht dessen Historie. Gefordert war eine dauerhafte, referenzierbare Lauf-Kennung je Historieneintrag.

**Geliefert:** Nichts. Sub-Issue von Epic #457, gemeinsam mit den übrigen Phase-2/3-Nacharbeiten bewusst zurückgestellt (siehe Epic-Abschlusskommentar: "Ticket-Hygiene … bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht").

**Verifikation:** `DirectorySyncPlanExecutor` und `GroupMembershipHistory` unverändert; keine Lauf-Kennung in den historisierten Zeilen erkennbar.

**Themen:** auth, security, spaces, rechtehistorie, verzeichnissynchronisation
