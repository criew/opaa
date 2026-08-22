# Issue #711 — feat(demo): Korpus-Generator für die fiktive Stadt Rheinfurt
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:L, demo
- PRs: #717 (2026-08-21)

**Laut Issue:** Deterministischer Generator unter `demo/`, der aus dem LHM-Dienstleistungen-Corpus (MIT) einen auf Rheinfurt umgeschriebenen Verwaltungskorpus erzeugt — 150–300 Dokumente über fünf Bibliotheken (Meldewesen, Kfz, Satzungen, Pressemitteilungen, interne Dienstanweisungen) in den Formaten `.md`, `.txt`, `.pdf`, `.docx`, `.pptx`. Werkzeugwahl für Binärformate ist Teil des Tickets, `eval/` muss unangetastet bleiben.

**Geliefert:** Deckungsgleich — 156 Dokumente über die fünf Bibliotheken, reine Python-Bibliotheken (`reportlab`, `python-docx`, `python-pptx`) statt externer Binärwerkzeuge, mit dokumentierten Determinismus-Fixes (`reportlab.rl_config.invariant`, eigener `zip_utils.normalize_zip_timestamps` gegen Zip-Zeitstempel). Reproduzierbarkeit über zwei Läufe und SHA-256-Manifest belegt. Fischereierlaubnis bewusst nicht im Korpus, wie im Drehbuch gefordert.

**Verifikation:** `demo/corpus/MANIFEST.sha256` und `demo/README.md` existieren im Worktree.

**Themen:** demo, korpus-generator, verwaltungskorpus, synthetische-daten
