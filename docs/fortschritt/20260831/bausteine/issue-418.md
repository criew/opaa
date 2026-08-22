# Issue #418 — feat(library): Bibliotheksliste an die Rechteformel angleichen und die eigene Rolle ausweisen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: #425 (2026-08-17)

**Laut Issue:** `GET /api/v1/libraries` listete nur Bibliotheken im Eigentum des Nutzers/seiner Gruppen plus organisationsweite, nicht aber solche mit reinem `AssetGrant` (`VIEWER`/`EDITOR`/`MANAGER`). Divergenz zu `LibraryAccessService.readableLibraryIds`. Gefordert: `KnowledgeLibraryService.listLibraries` an dieselbe Rechteformel angleichen, abgelaufene Grants ausschließen, `myRole` als Pflichtfeld in `LibraryListResponse`/`LibraryResponse` ergänzen, `AssetRole.USER` aus der OpenAPI-Spezifikation entfernen (Backend kannte den Wert seit #330 nicht mehr).

**Geliefert:** PR #425 setzt alle Punkte um: `listLibraries` nutzt `readableLibraryIds` plus neue Batch-Methode `effectiveRolesForReadableLibraries`; `myRole` in beiden Response-Typen, mit dokumentiertem Unterschied im System-Admin-Bypass (Liste bypassed nie zu OWNER, Einzelansicht schon); `AssetRole.USER` aus der Spezifikation entfernt. Der PR-Body dokumentiert zwei im eigenen Review gefundene, zusätzliche Bugs, die vor dem Merge noch behoben wurden: `myRole` konnte durch ungecachte/gecachte Divergenz `null` werden (Pflichtfeld verletzt), gefixt durch Floor auf `VIEWER`. Parity-Test zwischen `listLibraries` und `readableLibraryIds` sowie umfangreiche Integrationstests laut PR-Body vorhanden.

**Verifikation:** `myRole` ist im heutigen `backend/src/main/resources/openapi/opaa-api.yaml` als Pflichtfeld in `LibraryListResponse` vorhanden (Zeilen ~3290–3382), inklusive Beschreibung des Bypass-Unterschieds — passt zur PR-Beschreibung.

**Themen:** spaces, workspace, rechteformel, api, epic-198
