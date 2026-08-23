# Issue #203 — Space-asset association as pure curation
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, frontend, size:M, workspace
- PRs: #706 (2026-08-21)

**Laut Issue:** Eine Space-Bibliothek-Assoziation soll reine Kuratierung sein und keine Rechte gewähren oder Sichtbarkeit ändern. Kuratoren dürfen nur Bibliotheken zuordnen, auf die sie selbst Zugriff haben; der Bibliothekseigentümer sieht alle Assoziationen und kann jede lösen, wird aktiv benachrichtigt, wenn seine Bibliothek in einem Space mit engerem Leserkreis landet, und kann eine Bibliothek strikt-only markieren (Konflikte müssen beim Umschalten aufgelöst werden). Die Space-Ansicht filtert die Bibliotheksliste je Mitglied.

**Geliefert:** PR #706 setzt den Kernumfang um: `SpaceAssetAssociation`-Domänenmodell mit eigener Migration (051), `SpaceAssetAssociationService` mit den beschriebenen Berechtigungsregeln, API-Endpunkte (`GET/POST /spaces/{id}/libraries`, `DELETE`, `GET /libraries/{id}/spaces`), Retrieval-Integration in `ChatService`, sowie ein neuer, bewusst schmal geschnittener Benachrichtigungsmechanismus (`Notification`, `NotificationService`, Glocke im Frontend). Explizit ausgelassen wurden laut PR-Beschreibung: der Strikt-Modus/die strikt-only-Kennzeichnung (verschoben auf #204, da Strikt-Spaces noch nicht existieren) und der „@Space“-Chip in der Chat-Eingabe. Damit ist ein Teil der im Issue geforderten Abnahmekriterien (Strikt-Only-Konfliktauflösung) nicht Teil dieses PRs, sondern bewusst auf ein Folge-Issue verschoben.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAssetAssociation.java` und `backend/src/main/java/io/opaa/notification/NotificationService.java` existieren im heutigen Worktree-Stand.

**Themen:** spaces, workspace, retrieval, benachrichtigungen, auth
