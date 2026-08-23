# Issue #514 — feat(library): Verbindungstest für Quellkonfiguration im Erstellungsdialog
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #537 (2026-08-19)

**Laut Issue:** Ein „Verbindung testen"-Button sollte vor dem Anlegen die Quellkonfiguration serverseitig prüfen (Verzeichnis existiert/lesbar mit Dokumentzahl, Webverzeichnis erreichbar unter Proxy/Zugangsdaten, RSS-Feed abrufbar/parsebar mit Eintragszahl) — mit derselben HTTP-Client-Basis wie die echten Läufe und mindestens der Anlage-Berechtigung.

**Geliefert:** Wie gefordert. Neuer Endpunkt `POST /api/v1/libraries/source-test`, alle drei Typen implementiert, nutzt dieselben HTTP-Client-Bausteine (`AutoindexCrawlerService.buildHttpClient`/`buildAuthHeader`) wie die Indizierungsläufe — ausdrücklich auch für den RSS-Test, obwohl der RSS-Executor selbst Proxy/Zugangsdaten zu diesem Zeitpunkt noch nicht anwendete (#505, später behoben). `UPLOAD` liefert 400. Test ist optional, Anlegen bleibt auch ohne ihn möglich.

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceConnectionTestService.java` existiert; der Button ist heute Teil der Bibliotheksanlage (mittlerweile `LibraryCreatePage.tsx` statt des ursprünglichen `CreateLibraryDialog.tsx`, siehe #480).

**Themen:** backend, frontend, spaces, retrieval, ux
