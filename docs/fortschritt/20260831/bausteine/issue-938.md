# Issue #938 — Query: Einschlägige Satzungs-PDF fehlt in den Top-8 — Drehbuch-Frage 6 wird als thomas.klein verweigert
- Geschlossen: 2026-08-27 (completed)
- Labels: bug, backend, size:M, evaluation, demo
- PRs: keine (im Chunk nicht verlinkt — tatsächlich teilweise über PR #942 und PR #943 geliefert, siehe Verifikation)

**Laut Issue:** Fachliche Regressionsprüfung der Demo: `01_verwaltungsgebuehrensatzung.pdf` (§ 3, einschlägige Rechtsgrundlage zur Gebührenbefreiung) erscheint bei keinem Konto in den Top-8-Quellen der Drehbuch-Frage 6. Als `maria.weber` ist die Antwort nur zufällig korrekt (über eine interne Dienstanweisung), als `thomas.klein` (nur Satzungsbibliothek) wird komplett verweigert. Gefordert: Diagnose des tatsächlichen Rankings, dann ein datengetriebener Fix (Extraktions-/Chunking-Korrektur oder moderate `topK`-Anhebung 10–12).

**Geliefert — nur teilweise, mit offen dokumentierter Grenze:** Die Live-Diagnose ergab, dass eine `topK`-Anhebung wirkungslos wäre (Rückstand ~40 Ränge, Score-Abstand ~0,05) und stattdessen #933 (Contextual Chunking) als Fix-Rahmen gewählt wurde. Nach dessen Reindex zeigte sich ein **zweiter, im Issue nicht erwarteter Befund**: Drehbuch-Frage 1 (Personalausweis-Gebühr) lieferte 25,70 € statt 27,20 € wegen eines **Korpus-Datenwiderspruchs** zwischen zwei Dokumenten — behoben durch PR #942 (Korpusdaten angeglichen). Der eigentliche Frage-6-Fall (`thomas.klein`) blieb dagegen **ungelöst**: Die Satzung ist einchunkig und bekommt unter dem Contextual-Chunking-„Split-Gate“ bewusst keinen Präfix, der Rang bleibt bei 50/97. Maintainer-Entscheidung vom 27.08.2026: Issue auf den erfüllten Teil (Frage 1, Frage 6a) reduziert und geschlossen; der `thomas.klein`-Fall wird als bekannte, bewusst nicht weiterverfolgte Grenze der reinen Vektorsuche dokumentiert (PR #943) — eine Hybrid-Suche (BM25 + Vektor) wäre der passende Mechanismus, ist aber nicht beauftragt.

**Verifikation:** `demo/corpus/leistungen-meldewesen-ausweise/002_personalausweis-oder-reisepass-abholen.md` (PR #942) und der Abschnitt „Bekannte offene Schwächen“ in `docs/features/retrieval-algorithm.md` (PR #943) existieren im Worktree.

**Themen:** retrieval, query, demo, korpusdaten, qualitätssicherung, bekannte-grenzen, epic-912
