# Issue #551 — fix(library): Verbindungstest-Meldungen ohne Umlaute und mit falschem Singular
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #571 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #549 stammender, vorbestehender Befund: Die nutzersichtbaren Meldungen des Verbindungstests (`SourceConnectionTestService`) verwendeten Ersatzschreibweisen statt Umlauten („unterstuetzte", „zulaessige Groesse") und waren im Singular grammatisch falsch („1 unterstuetzte Dokument"). AGENTS.md verlangt korrektes Deutsch für nutzerseitige API-Meldungen. Hinweis im Ticket: der E2E-Test aus #547 schreibt den Wortlaut zeichengenau fest und muss mitgezogen werden; verwandt mit #448 (gleiche Fehlerklasse im Grants-Backend).

**Geliefert:** Alle betroffenen Meldungen auf echte Umlaute und korrekte Singular-/Pluralformen umgestellt, neue Hilfsmethode `supportedDocumentPhrase(count)` für konsistente Adjektiv-Kongruenz. MSW-Mock und E2E-Spec-Datei entsprechend mitgezogen. Reproduktionsnachweis mit konkretem Vorher/Nachher-Vergleich erbracht.

**Verifikation:** `SourceConnectionTestService.java` enthält `supportedDocumentPhrase` (2 Treffer) im Worktree.

**Themen:** library, doku, sprachqualität, retrieval
