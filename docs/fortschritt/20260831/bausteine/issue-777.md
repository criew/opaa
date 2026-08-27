# Issue #777 — Mitglieder hinzufügen für normale Nutzer kaputt: Benutzersuche nutzt SYSTEM_ADMIN-Endpunkt; dazu zwei UI-Korrekturen der Mitgliederverwaltung
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, frontend, size:M, workspace
- PRs: #778 (2026-08-23)

**Laut Issue:** Drei zusammenhängende Befunde aus einem Klick-Test auf der Demo-Instanz: (1) Die Nutzerauswahl in vier Frontend-Stellen (`SpaceManagementPage`, `SpaceCreatePage`, `LibraryCreatePage`, `LibraryGrantsDialog`) rief `GET /v1/admin/users` auf, das seit der Organisationsgrenze (#199) `SYSTEM_ADMIN` verlangt — normale Nutzer bekamen ein stillschweigend geschlucktes 403 und eine leere Autocomplete. (2) Der Hinweistext beim Standard-Space mit einem Mitglied ersetzte statt ergänzte das Hinzufügen-Formular, obwohl der Standard-Space laut Spezifikation „ein Space wie jeder andere" ist. (3) Die Eigentümer-Zeile zeigte ein editierbares Rollen-Dropdown, dessen Nutzung immer in einen Backend-Fehler lief.

**Geliefert:** Alle drei Befunde behoben. Neuer Endpunkt `GET /v1/users` (`UserSearchController`), erreichbar für jeden angemeldeten Nutzer der eigenen Organisation, liefert eine schmalere `UserSummaryResponse` ohne `systemRole`; alle vier Frontend-Stellen wurden umgestellt. `GET /v1/admin/users` blieb unverändert SYSTEM_ADMIN-only. Der Standard-Space-Hinweis ergänzt jetzt das Formular statt es zu ersetzen; die Eigentümer-Zeile zeigt ein statisches Badge. Als Nebeneffekt wurde die alte `isSystemAdmin`-Gating-Logik in `LibraryGrantsDialog` entfernt (schließt einen in #423 offen gelassenen Folgepunkt).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserSearchController.java` existiert im Worktree weiterhin.

**Themen:** spaces, auth, workspace, frontend
