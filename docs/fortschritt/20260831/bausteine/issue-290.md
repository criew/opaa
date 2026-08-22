# Issue #290 — fix(ci): Fehlzuordnungen im Epic-Report beheben
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:M, ci
- PRs: #291 (2026-08-02)

**Laut Issue:** Der erste Lauf des epic-orientierten Reports (aus #285/#286) zeigte drei Fehlzuordnungen, alle durch Raten aus Fließtext statt strukturierten Daten: (1) Aufzählungsmarker wie `#1` in Epic #60 wurden als Ticketnummern gelesen, (2) jede `#N`-Erwähnung im Fließtext zählte als Ticket (Epic #198 erschien als Ticket von Epic #224), (3) Beispieltexte in PR-Beschreibungen (`Closes #221` als Testfall-Zitat) wurden als echte Verknüpfung gewertet.

**Geliefert:** Ticketlisten werden nur noch aus Checkbox-Einträgen gelesen, bei denen die Nummer direkt auf die Checkbox folgt; nur existierende Issues zählen, keine Epics; PR-Zuordnung nutzt GitHubs `closingIssuesReferences` statt Body-Parsing, in einer gebündelten GraphQL-Abfrage pro Tag. Wirkung an echten Daten belegt (Epic #60 verschwindet, #224 korrigiert von 4/17 auf 4/14, PR #286 korrekt nur noch #285 statt fünf Fehlzuordnungen).

**Verifikation:** Bestätigt durch denselben Treffer wie bei #285 — `closingIssuesReferences` ist in `.github/scripts/daily_report.py` vorhanden.

**Themen:** ci, tagesreport, agenten-organisation
