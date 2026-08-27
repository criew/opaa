# Issue #903 — test(backend): Spring-Testkontexte konsolidieren (~19 → ≤10) — Meta-Annotation für Indexing, geteilte Mock-Configs
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (im Chunk nicht verlinkt — tatsächlich über PR #905 und PR #908 geliefert, siehe Verifikation)

**Laut Issue:** Folgearbeit aus Epic #826. Von ~19 Spring-Testkontexten sollten durch eine dritte kanonische Meta-Annotation (`@OpaaIndexingIntegrationTest`), geteilte `@TestConfiguration`-Mocks und die Umstellung mechanischer Einzelfälle höchstens 10 Kontexte übrig bleiben, bei gemessener Verbesserung der `./gradlew test --rerun`-Laufzeit (Basis: 9 m 53 s) und ohne Abschwächung von Assertions.

**Geliefert:** PR #905 führte `@OpaaIndexingIntegrationTest` ein, PR #908 die Mock-Konsolidierung (Schritte 2–4). Laut Abschlusskommentar im Issue: Laufzeit **9 m 53 s → 3 m 13 s** (−67 %), Kontextzahl ~21 → **17** statt der geforderten ≤10. **Abweichung vom Issue, offen benannt und vom Maintainer akzeptiert:** Das Kontextziel wurde bewusst nicht erreicht — die verbleibenden 17 Kontexte stecken in echten Konfigurationsunterschieden (Konfiguration als Testsubjekt, `@MockitoSpyBean` auf echten Beans, einzigartige Race-Mocks); weiteres Zusammenlegen hätte Testsubstanz gekostet. Das Laufzeitziel wurde damit deutlich übererfüllt, das Kontextzahl-Kriterium nachträglich als weniger wichtig eingestuft.

**Verifikation:** `backend/src/test/java/io/opaa/test/OpaaIndexingIntegrationTest.java` und zugehörige Mock-/Reset-Klassen existieren im Worktree. Commits `0b3512c8` (#905) und `950eb4b3` (#908) in der Historie vorhanden.

**Themen:** testinfrastruktur, ci, backend, epic-826, refactoring
