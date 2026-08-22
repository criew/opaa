# Issue #686 — feat(space): Datenquellen-Zuordnung Space ↔ Wissensbibliothek (API und Retrieval)
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend
- PRs: #706 (2026-08-21)

**Laut Issue:** Für den Space-Assistenten (#594) fehlte die API-Grundlage für den Schritt "Datenquellen zuordnen": kein `libraryIds`-Feld, keine Zuordnungs-Ressource, keine Space↔Bibliothek-Beziehung im Backend. Gefordert: Domänenmodell + Persistenz (n:m), OpenAPI-Erweiterung mit Rechteprüfung (mind. Leserecht), Retrieval nutzt die Zuordnung als Standard-Suchbereich, Migrationstest. Frontend-Folgearbeit ausdrücklich als separat schätzbar markiert.

**Geliefert:** Deutlich mehr als im Issue verlangt — PR #706 liefert gemeinsam mit dem größeren Issue #203 die vollständige Umsetzung inklusive Frontend (Datenquellen-Schritt im Assistenten, Pflege in der Space-Verwaltung, Eigentümer-Sicht "Bereitgestellt in") sowie einen neuen Benachrichtigungsmechanismus (`Notification`, `NotificationService`, Glocke mit Badge), der im Issue nicht gefordert war, aber als Grundstein für ein späteres Postfach eingeordnet wird. Rechtemodell wie gefordert als reine Kuratierung umgesetzt: Zuordnung ändert keine effektiven Leserechte. Strikt-Modus (#204) und `@Space`-Chip im Chat wurden bewusst ausgelassen.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAssetAssociation.java` und `SpaceAssetAssociationService.java` existieren im Worktree.

**Themen:** spaces, retrieval, api, rechtemodell, benachrichtigungen, backend, frontend
