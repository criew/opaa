# Issue #202 — Asset permissions and permission-aware vector search
- Geschlossen: 2026-08-04 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #309 (2026-08-04)

**Laut Issue:** Zentrale Lücke: `QueryService` filterte die Ähnlichkeitssuche bislang gar nicht. Gefordert: `AssetGrant` (Subjekt user/group → Rolle USER/VIEWER/EDITOR/MANAGER/OWNER, eigene Rangordnung getrennt von `SpaceRole`, kein Rollenname in beiden Systemen), jeder Grant von Anfang an mit optionalem Ablaufdatum, `readableLibraries(user)` = direkte Grants + Gruppen-Grants + organisationsweite Bibliotheken (Space-Zugehörigkeit fließt explizit nicht ein), Caching mit sofortiger Invalidierung, `library_id`-Metadatenfilter als Teil der `VectorStore`-Suche (kein Post-Filter). Zielvorgabe: Rechteauflösung soll unter 50 ms zur Query-Zeit hinzufügen.

**Geliefert:** PR #309 liefert `AssetGrant`/`AssetRole` mit disjunkter Rangordnung, `LibraryAccessService` als Ersatz der groben `canRead`/`canManage`-Zwischenlösung aus #201, `QueryService`-Filterung auf `readableLibraryIds` als Teil des Vektorsuche-Aufrufs, kein System-Admin-Bypass im Suchpfad. Wesentlicher Verhaltenswechsel gegenüber #201: Gruppen-Eigentümerschaft einer Bibliothek gewährt Mitgliedern keine automatischen Verwaltungsrechte mehr — nach drei Review-Runden erhält die Gruppe bei Erstellung nur `MANAGER`, der erstellende Nutzer persönlich `OWNER`. Die 50-ms-Zusage aus den Akzeptanzkriterien konnte laut PR **nicht** als belastbare, lastgetestete Garantie nachgewiesen werden — nur ein einzelner kalter Messwert (43 ms) mit einem großzügigeren 100-ms-Regressionstest statt harter 50-ms-Assertion; das Issue selbst hatte dies bereits als offenen Punkt markiert. Migration 013 vergibt beim Backfill für gruppen-eigene Bestandsbibliotheken bewusst keinen `OWNER`-Grant, nur `MANAGER` an die Gruppe (Entscheidung im Review bestätigt). Zwei Folge-Punkte wurden ausdrücklich nicht mitgenommen: fehlender `ResponseStatusException`-Handler im `GlobalExceptionHandler` (kommentiert, kein Issue benannt) und eine Race in `GroupService.deleteGroup` (ausgelagert nach #310).

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrant.java` und `backend/src/main/java/io/opaa/library/LibraryAccessService.java` existieren im heutigen Worktree.

**Themen:** security, retrieval, auth, spaces, migration, performance
