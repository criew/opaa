# Issue #835 — build: OpenAPI-doLast-Löschliste ableiten und Eval-Tasks deduplizieren
- Geschlossen: 2026-08-24 (completed)
- Labels: backend, size:S, ci
- PRs: #857 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund T2). Zwei mechanische Duplikationen in `backend/build.gradle.kts`: die `doLast`-Löschliste im OpenAPI-Task sollte aus `typeMappings` abgeleitet statt separat gepflegt werden; die Eval-Tasks (`evaluateRetrieval`/`checkRetrievalBaseline` je Domäne) sollten über eine Registrierfunktion statt Kopie definiert werden.

**Geliefert:** Löschliste wird jetzt mechanisch aus `typeMappings` berechnet (bleibt als Sicherheitsnetz, da die aktuelle Generator-Version laut PR ohnehin keine Modelldatei mehr für vollständig gemappte Typen emittiert — beide Varianten sind in der Praxis No-Ops). Eval-Tasks über `registerEvalDomain(...)` zusammengeführt, Filter jetzt über die vollqualifizierte Testklasse statt Suffix-Wildcard (löst nebenbei eine Wildcard-Falle). Bewusste Verengung dokumentiert: `evaluateRetrieval` lief vorher implizit über den ganzen `evalTest`-Source-Set abzüglich Excludes, jetzt nur noch über die jeweilige Harness-Klasse.

**Verifikation:** `backend/build.gradle.kts` im Worktree vorhanden; PR dokumentiert Vorher/Nachher-Vergleich von `openApiGenerate`-Output (79 Dateien, identisch bis auf Zeitstempel) und `gradle tasks --all`.

**Themen:** build, gradle, ci, openapi, projektsetup
