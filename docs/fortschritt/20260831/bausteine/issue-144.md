# Issue #144 — security(space): Mitgliederliste eines Space nur für Space-Admins und Eigentümer
- Geschlossen: 2026-08-20 (completed)
- Labels: security
- PRs: #674 (2026-08-20)

**Laut Issue:** Die vollständige Mitgliederliste eines Space (inkl. Klarnamen) wurde jedem Mitglied unabhängig von der Rolle preisgegeben — sowohl über `GET /spaces/{id}` als auch `GET /spaces/{id}/members`. Gefordert: Beschränkung auf `ADMIN`, Eigentümer und System-Admin, aggregierte Rollenzählung bleibt für alle sichtbar.

**Geliefert:** PR #674 setzt die im Issue empfohlene „sauberere" Variante um: Feld `members` wurde aus `SpaceResponse` entfernt (OpenAPI-Spec zuerst geändert), `SpaceService.listMembers` prüft jetzt zusätzlich zur Mitgliedschaft die Rolle `ADMIN` (neue Methode `requireMemberListViewer`). Frontend lädt Mitglieder nur für `ADMIN` über einen eigenen Store-Slice, Nicht-Admins sehen stattdessen die aggregierte Rollenzählung als Chips. Reproduktionsnachweis mit rotem/grünem Testlauf dokumentiert.

**Verifikation:** `SpaceService.java` enthält `requireMemberListViewer` (Zeilen 187, 190, 719 laut Grep) — die Änderung ist im heutigen Code vorhanden.

**Themen:** security, spaces, rechteverwaltung, mitbestimmung
