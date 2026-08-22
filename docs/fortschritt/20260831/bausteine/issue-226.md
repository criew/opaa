# Issue #226 — feat(eval): Golden Dataset aus dem Frontmatter des Korpus ableiten
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, size:M, evaluation
- PRs: #273 (2026-08-02)

**Laut Issue:** Aus dem YAML-Frontmatter des Comic-Korpus (#225) soll ein Golden Dataset mit mindestens 100 kuratierten Fällen über fünf Frage-Kategorien (`attribute_lookup`, `entity_description`, `multi_attribute_filter`, `numeric_range`, `crosslingual`) abgeleitet werden; Ground Truth rein rechnerisch aus den Feldern, nicht von einem LLM geschätzt. Mindestens 30 Fälle auf Deutsch, Filterfragen mit 2–15 Treffern, alle Fälle manuell durchgesehen, Kuratierungsregeln dokumentiert.

**Geliefert:** `eval/generator/generate_golden_dataset.py` erzeugt `eval/golden/comic-characters.json` mit 121 kuratierten Fällen (87 en / 34 de) über alle fünf Kategorien, plus `comic-characters.candidates.json` (477 automatisch validierte Rohkandidaten) und `comic-characters.discarded.json` (356 verworfene Kandidaten mit Begründung) als Kuratierungsnachweis. Zwei zusätzliche verbindliche Filter eingeführt (`overall_score is not null`, Plausibilitätsprüfung verunreinigter Quellspalten `first_appearance`/`occupation`), ein Übersetzungsbug bei deutschen Mehrwort-Werten behoben. Abweichung: `entity_description` über Geburtsort+Beruf ist mit 4 von 20 möglichen Fällen unterrepräsentiert (uneinheitliche Quelldaten), bewusst nicht entfernt, da Ground Truth korrekt bleibt.

**Verifikation:** `eval/golden/comic-characters.json`, `.candidates.json` sowie `eval/golden/README.md` existieren im heutigen Code; `eval/generator/generate_golden_dataset.py` vorhanden.

**Themen:** eval, retrieval, golden-dataset, python-tooling, doku
