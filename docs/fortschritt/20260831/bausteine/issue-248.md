# Issue #248 — feat(ci): Täglichen Projektreport als GitHub-Pages-Seite mit Atom-Feed veröffentlichen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, enhancement, size:M, ci
- PRs: #259 (2026-08-02)

**Laut Issue:** Ein täglich laufender Workflow soll abgeschlossene/neue Issues, gemergte/offene PRs und CI-Status des Hauptbranchs zusammentragen, mit einer modellgenerierten Fließtext-Zusammenfassung (optional, kein Ausfall bei fehlendem API-Key) als HTML-Seite auf GitHub Pages veröffentlichen, dazu ein Atom-Feed. Zeitgesteuert und manuell mit wählbarem Datum startbar. Kein SMTP-Versand, keine Wochen-/Monatsberichte. Tage ohne Aktivität sollen keinen Report erzeugen.

**Geliefert:** `.github/workflows/daily-report.yml` (Zeitsteuerung, Veröffentlichung im Branch `gh-pages`), `.github/scripts/daily_report.py` (Datenerhebung über GitHub-CLI, Zusammenfassung, Seiten-/Feed-Erzeugung), `docs/tagesreport.md` (Bedienung). Rohdaten liegen als JSON in `gh-pages`, Übersichtsseite und Feed werden bei jedem Lauf neu daraus erzeugt. Obergrenze von 25 Einträgen je Abschnitt im Prompt für die Zusammenfassung. Das Secret `OPAA_OPENAI_API_KEY` war zum Merge-Zeitpunkt bewusst noch nicht gesetzt — Reports liefen zunächst ohne Fließtext-Zusammenfassung. Nach dem Merge war ein einmaliger manueller Schritt nötig (GitHub Pages auf `gh-pages` stellen), der nicht Teil des PRs war, sondern als Anleitung im PR-Body stand.

**Verifikation:** `.github/scripts/daily_report.py`, `.github/workflows/daily-report.yml` und `docs/tagesreport.md` existieren im heutigen Code. Ob GitHub Pages tatsächlich aktiv geschaltet wurde und der Feed seither läuft, wurde nicht geprüft (außerhalb des Repository-Inhalts).

**Themen:** ci, doku, agenten-organisation
