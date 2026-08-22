# Issue #304 — eval(golden): category:crosslingual und language:de sind identische Fallmengen
- Geschlossen: 2026-08-20 (completed)
- Labels: size:S, evaluation
- PRs: #673 (2026-08-20)

**Laut Issue:** Im Golden Dataset sind `category:crosslingual` und `language:de` konstruktionsbedingt exakt dieselbe Fallmenge (34 Fälle) — der Retrieval-Regressionsjob prüft dadurch acht statt vier Mal dieselben Daten und suggeriert breitere Abdeckung. Gefordert war eine dokumentierte Entscheidung: getrennte Gruppen trotz Identität, Generator-Erweiterung um weitere Sprachen, oder Konsolidierung.

**Geliefert:** PR #673 entscheidet sich für Konsolidierung: `language:de` entfällt als Baseline-Gruppe, `category:crosslingual` bleibt (benennt die fachliche Eigenschaft). `BaselineComparator.compare` überspringt die vom Report weiterhin gelieferte `language:de`-Gruppe gezielt (`REDUNDANT_LANGUAGE_GROUP`). Die im Issue alternativ vorgeschlagene Generator-Erweiterung wurde als grundsätzlicherer, aber hier bewusst nicht gegangener Weg dokumentiert (eigenständiges Vorhaben, neuer Corpus-Lauf nötig). Entscheidung ist in `eval/baseline/README.md` und ADR-0013 (Nachtrag) festgehalten, wie gefordert.

**Verifikation:** `backend/src/evalTest/java/io/opaa/eval/BaselineComparator.java` existiert im Worktree und enthält `REDUNDANT_LANGUAGE_GROUP`.

**Themen:** evaluation, retrieval, golden-dataset, ci
