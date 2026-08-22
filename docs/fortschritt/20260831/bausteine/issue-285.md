# Issue #285 — feat(ci): Report-Zusammenfassung an Epics ausrichten und kürzen
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, ci
- PRs: #286 (2026-08-02)

**Laut Issue:** Die Zusammenfassung des Tagesreports sollte statt einer unstrukturierten Liste von Vorgängen entlang der Epics gegliedert werden (max. ein Absatz je aktivem Epic plus ein Absatz für Vorgänge ohne Epic-Bezug), mit Bewegung des Tages und Gesamtfortschritt je Epic. Zuordnung sollte aus der Ticketliste im Epic-Body kommen, ohne Ratewerte, und ohne Abfrage je Ticket.

**Geliefert:** `daily_report.py` erhebt Epics über das Label `epic`, liest Ticketlisten aus dem Body, ordnet Pull Requests über die von GitHub gepflegte Verknüpfung zu und gibt Kennzahlen fest im Prompt vor (nicht dem Modell zum Abzählen überlassen), weil ein erster Versuch mit modellseitigem Abzählen durchweg falsche Zahlen lieferte. Zusammenfassung sank von ~250 auf 183 Wörter. Rückwärtskompatibilität mit älteren Reports ohne die neuen Felder wurde geprüft.

**Verifikation:** `.github/scripts/daily_report.py` enthält 25 Treffer für "Epic"/"closingIssuesReferences" — die epic-orientierte Struktur ist im aktuellen Code vorhanden.

**Themen:** ci, tagesreport, agenten-organisation
