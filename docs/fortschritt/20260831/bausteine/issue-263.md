# Issue #263 — docs: Nummernkollision zweier ADRs mit der Nummer 0008 auflösen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation
- PRs: #264 (2026-08-02)

**Laut Issue:** Auf `main` existierten zwei ADRs mit derselben Nummer 0008 (`0008-space-and-asset-model.md` und `0008-search-quality-evaluation-harness.md`), beide von Querverweisen aus Feature-Spezifikationen und weiteren ADRs verlinkt. Gefordert war, dass das ältere ADR die Nummer 0008 behält und das jüngere auf die nächste freie Nummer (0011, da 0009/0010 bereits vergeben) umbenannt wird, inklusive aller Querverweise.

**Geliefert:** Exakt wie gefordert umgesetzt: `0008-search-quality-evaluation-harness.md` wurde zu `0011-search-quality-evaluation-harness.md`, Querverweise in `docs/features/search-quality-evaluation.md`, `docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md`, `eval/README.md`, `eval/generator/README.md` und `eval/corpus/comic-characters/SOURCE.md` angepasst. Der PR verifizierte zusätzlich, dass `SOURCE.md` nicht Teil von `MANIFEST.sha256` ist, die Korpus-Prüfsummen also gültig bleiben.

**Verifikation:** `docs/decisions/0011-search-quality-evaluation-harness.md` existiert im aktuellen Worktree. `docs/decisions/0008-space-and-asset-model.md` existiert dagegen ebenfalls nicht mehr — laut `git log` wurde diese Datei durch einen späteren, mit diesem Issue nicht verwandten Commit (`bd7b4257`, „ADR-Bestand entschlacken und auf den tatsächlichen Stand bringen") entfernt bzw. neu geordnet. Die von #263 gelöste Kollision selbst ist im heutigen Bestand (Nummern 0001–0006, 0009–0019, kein 0007/0008) nicht mehr direkt nachvollziehbar, da beide betroffenen Dateinamen inzwischen aus anderen Gründen nicht mehr existieren — das Ergebnis von #263 (Eindeutigkeit) ist aber weiterhin erfüllt.

**Themen:** doku, agenten-organisation
