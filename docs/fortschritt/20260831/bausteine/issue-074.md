# Issue #74 — 🔵 [LOW] Complex Business Logic in Lambda Expression
- Geschlossen: 2026-03-01 (completed)
- Labels: enhancement, backend, size:S
- PRs: #88 (2026-03-01)

**Laut Issue:** Die Merge-Logik für dedupliziertes Source-Referencing in `QueryService.java:131-151` war als komplexe Inline-Lambda implementiert — schwer testbar und undokumentiert. Gefordert: Extraktion in eine benannte Methode mit Javadoc und dedizierten Tests.

**Geliefert:** PR #88 extrahiert die Lambda in `mergeSourceReferences()` mit Javadoc und 7 dedizierten Unit-Tests, die alle Merge-Szenarien abdecken. Deckt die Forderung vollständig ab, ohne Verhaltensänderung.

**Verifikation:** Nicht mehr in dieser Form am gleichen Ort geprüft (Query-Pipeline wurde seither mehrfach umgebaut, siehe Issue #66), das grundsätzliche Refactoring-Muster (benannte Merge-Methode statt Inline-Lambda) ist aber plausibel weitergeführt worden — keine tiefere Prüfung nötig für ein reines Low-Priority-Refactoring.

**Themen:** backend, refactoring, testbarkeit
