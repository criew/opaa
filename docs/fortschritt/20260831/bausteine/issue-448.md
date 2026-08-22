# Issue #448 — Deutsche Fehlermeldungen im Grants-Backend: rohe Enum-Namen und fehlende Umlaute
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #576 (2026-08-20)

**Laut Issue:** Nutzerseitige Fehlermeldungen in `AssetGrantService` enthielten rohe Enum-Namen (`OWNER`, `MANAGER`) statt deutscher Rollenbezeichnungen sowie umlautfreie Schreibweisen (`persoenliche`, `koennen`, `aendern`). Gefordert war eine serverseitige Mapping-Funktion analog zu `assetRoleLabel` im Frontend, plus eine kurze Durchsicht benachbarter Meldungen in `KnowledgeLibraryService`/`LibraryDocumentService`.

**Geliefert:** Neue private Zuordnung `roleLabel(AssetRole)` in `AssetGrantService`, liefert deutsche Rollenbezeichnungen (Betrachter/Bearbeiter/Verwalter/Eigentümer). In einer zweiten Review-Runde wurden zwei zunächst übersehene Meldungen (Last-active-Owner-Guard bei `revokeGrant`/`upsertGrant`) sowie die zeichengenau nachgezogenen MSW-Mocks in `frontend/src/mocks/handlers.ts` korrigiert. Abweichung vom Issue: Die im Issue erwähnten Meldungen zur „persönlichen Bibliothek" existierten laut PR-Beschreibung im aktuellen Stand von `AssetGrantService.java` gar nicht mehr, da persönliche Bibliotheken mit #522 bereits abgeschafft wurden — dieser Teil des Issues war zum Zeitpunkt der Umsetzung bereits gegenstandslos. Die geforderte Durchsicht von `KnowledgeLibraryService`/`LibraryDocumentService` wird im PR-Body nicht ausdrücklich erwähnt, laut Dateiliste wurden nur `AssetGrantService.java`, der zugehörige Test und `handlers.ts` geändert.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantService.java` existiert im heutigen Stand. Reproduktionsnachweis im PR mit konkreten roten Testläufen für beide Runden dokumentiert.

**Themen:** library, grants, fehlermeldungen, i18n, backend
