# Issue #271 — security(auth): AdminController setzt die Organisationsgrenze nicht durch
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S, security, auth
- PRs: #679 (2026-08-20)

**Laut Issue:** Bei der Nachprüfung von PR #254 fiel auf, dass `AdminController` die mit #199 eingeführte Organisationsgrenze nicht durchsetzt: `GET /api/v1/admin/users` listete Nutzer aller Organisationen, `POST /api/v1/admin/users/{id}/role` konnte Systemrollen organisationsübergreifend ändern. Aktuell nicht ausnutzbar (nur eine Organisation geseedet), aber vor Einführung einer zweiten Organisation zwingend zu beheben. Gefordert: Nutzerliste auf die eigene Organisation scopen, Rollenänderung an org-fremden Nutzern mit 404 ablehnen, weitere Controller auf denselben Lückentyp prüfen.

**Geliefert:** PR #679 scopt `UserService#findAllInOrganization` (neues `UserRepository#findByOrganizationId`) und lässt `updateRole` den Zielnutzer über `findByIdAndOrganizationId` auflösen — org-fremd führt zu 404 (`UserNotFoundException`), analog zu `SpaceService`. Neuer HTTP-Ebenen-Test `AdminControllerOrganizationBoundaryIntegrationTest`. Zusätzlich wurden laut PR-Body alle anderen Controller (Group, Library, AssetGrant, Audit, Chat, DirectorySync, Branding) systematisch geprüft — keine weiteren Lücken gefunden, kein Folge-Issue nötig. Keine Abweichung vom Issue.

**Verifikation:** `AdminController.java` ruft `userService.findAllInOrganization(currentUser.getOrganizationId())` auf — die im PR beschriebene Umsetzung ist im aktuellen Code vorhanden.

**Themen:** auth, security, spaces, backend
