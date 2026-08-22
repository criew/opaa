# Issue #410 — docs: Backlog-Sichtung abschließen und Statusaussage zum Upload berichtigen
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, size:S
- PRs: #411 (2026-08-15)

**Laut Issue:** Zwei Punkte: (1) `docs/discussions/discussion-backlog-neuausrichtung.md` sollte die vier Kategorietabellen (abgearbeitet) entfernen, den Abschnitt „Lücken" behalten und die Einleitung auf den abgeschlossenen Stand bringen. (2) `docs/STATUS.md` behauptete unter Bereich B fälschlich, „Upload über die Weboberfläche und die REST-API" sei bereits gebaut — tatsächlich gab es keinen `multipart`-Endpunkt und `documents` führte keine einbringende Person.

**Geliefert:** PR #411 entfernt die vier Kategorietabellen aus dem Sichtungsdokument, aktualisiert die Einleitung mit dem Ergebnis jeder Kategorie und belässt den Abschnitt „Lücken" unverändert. `STATUS.md` bekommt unter einer eigenen Überschrift „Nicht gebaut — obwohl es hier lange anders stand" die Korrektur zum Upload. Deckt sich mit dem Issue-Umfang ohne Abweichung.

**Verifikation:** Zum Zeitpunkt der Inventur (nach #420/#422) ist der Upload inzwischen tatsächlich gebaut — `docs/STATUS.md` Zeile 25 und Zeile 99 führen ihn heute korrekt unter „Gebaut" (`POST /api/v1/libraries/{libraryId}/documents`). Die hier vorgenommene Korrektur war also zum damaligen Zeitpunkt richtig und wurde später durch echte Umsetzung (#420) überholt — kein Widerspruch, sondern normale Weiterentwicklung.

**Themen:** doku, projektsetup, backlog, upload
