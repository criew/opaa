# Issue #18 — feat: implement OPAA MVP (Epic)
- Geschlossen: 2026-02-28 (completed)
- Labels: epic, mvp
- PRs: keine (Epic, keine eigene Umsetzung)

**Laut Issue:** Epic zur Bündelung der gesamten MVP-Umsetzung (docs/MVP.md): Q&A über indizierte Dokumente per Web-UI mit Quellenreferenzen. Führt Phasen 1–5 mit den Einzeltickets #6–#17, #19, #23 sowie Polish-Tickets (#37, #40–#44, #47, #49, #50, #53) als Checkliste.

**Geliefert:** Kein eigener PR — das Epic bündelt ausschließlich die verlinkten Einzeltickets, die jeweils eigenständig gemergt wurden (siehe entsprechende Bausteine #14–#17, #19, #23, #37 u.a.). Der Issue-Body enthält eine Abschluss-Verifikationstabelle (Stand 2026-02-28), die alle 8 MVP-Erfolgskriterien als erfüllt ausweist: Indizierung, Q&A-Flow, Quellenanzeige, duale LLM-Unterstützung (OpenAI/Ollama), getrennte Konfiguration für Chat/Embedding, Docker Compose (3 Dienste), lokale Entwicklung ohne Docker (Mock-Profil/MSW), UI-Platzhalter (Feedback-Buttons, Access-Level-Badges).

**Verifikation:** Als Epic kein eigenständiger Code-Verifikationsgegenstand; die einzelnen referenzierten Tickets sind separat verifiziert (siehe Bausteine #14–#17, #19, #23, #37). Viele der zum Abschlusszeitpunkt genannten Komponenten (AdminDrawer, IndexingController, SourceCard, MVP-VERIFICATION.md) wurden seither im Rahmen der Weiterentwicklung (Bibliotheks-Modell, Spaces, Fußnoten-Zitate) ersetzt oder entfernt — die MVP-Grundarchitektur (Indexing → Vektorsuche → LLM-Antwort mit Quellen) besteht aber fort.

**Themen:** epic, mvp, projektsetup, dokumentation
