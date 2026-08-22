# Issue #544 — feat(library): Verbindungstest auch im Bearbeiten-Dialog der Quellkonfiguration
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #615 (2026-08-20)

**Laut Issue:** Der Verbindungstest aus #514 (`POST /api/v1/libraries/source-test`) stand nur im Erstellungsdialog zur Verfügung, nicht im Bearbeiten-Dialog (#516). Nicht trivial nachrüstbar, da `SourceConnectionTestRequest` keine `libraryId` kannte und Zugangsdaten im Klartext erwartete — eine bestehende passwortgeschützte Quelle ließe sich ohne Neueingabe nicht testen. Gefordert: optionale `libraryId` im Request (Spec zuerst), die bei fehlenden neuen Zugangsdaten serverseitig die gespeicherten Zugangsdaten der Bibliothek verwendet (mind. MANAGER-Rolle), plus „Verbindung testen"-Button im `EditLibrarySourceDialog`.

**Geliefert:** Genau wie gefordert umgesetzt: optionale `libraryId`, Fallback auf gespeicherte Zugangsdaten nur bei leerem Zugangsdaten-Feld, Berechtigungsprüfung über `LibraryAccessService#requireRole` (404 ohne Zugriff, 403 bei zu geringer Rolle, `systemAdmin`-Bypass analog `updateLibrary`), `sourceType`-Konsistenzprüfung. Same-Origin-Regel für den Zugangsdaten-Fallback wurde in eine gemeinsame Klasse `SourceOriginMatcher` extrahiert. In der ersten Review-Runde wurde ein kritischer Bug behoben: `SourceOriginMatcher` verglich Hosts ursprünglich per `Objects.equals`, was bei Hostnamen mit Unterstrich (`URI.getHost()` liefert dann `null`) zwei völlig verschiedene Hosts fälschlich als gleichen Origin durchgehen ließ — Fix per Delegation an `AutoindexCrawlerService#sameOrigin`. Ebenfalls nachgebessert: fehlender `systemAdmin`-Durchgriff. Ein bekannter Proxy/insecureSsl-Exfiltrationsweg wurde bewusst nicht in diesem PR behoben, sondern als separates Follow-up-Issue vermerkt (keine Nummer im Body genannt).

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceOriginMatcher.java` existiert im Worktree.

**Themen:** library, retrieval, auth, security, ui
