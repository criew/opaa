# Issue #844 — test(backend): Sonderkontexte auf kanonische Test-Signaturen zurückführen
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M
- PRs: keine

**Laut Issue:** Teil von Epic #826, Phase 2/3-Übergang (Befund T3, Schritt 2 von 2). Nach Einführung der Meta-Annotationen sollten ~14 Testklassen mit eigener `@DynamicPropertySource`, ~5 mit testlokalen `@Import(...TestConfig)` und diverse `@MockitoBean`-Kombinationen einzeln geprüft und wo möglich zurückgeführt werden; Migrations-Fixture-Ketten bewerten.

**Geliefert:** Nicht umgesetzt (not planned). Der zugehörige PR #865 zu #843 hat den Umfang dieses Issues faktisch bereits mit erledigt: Das Inventar dort deckt exakt die dort beschriebenen Sonderkonfigurationen ab und kommt zum Schluss, dass die verbleibenden Abweichungen (`@MockitoBean`, `@DynamicPropertySource`) fachlich nötig sind, da Spring beides zwingend in den Kontext-Cache-Schlüssel aufnimmt — eine weitere Rückführung ist strukturell nicht möglich. Die 8 gefundenen Ballast-Fälle (duplizierte Container) wurden bereits in #843 bereinigt. Das Issue selbst formuliert das im Titel bereits vorweg: „Erweitert um den Umfang von #844 (dort geschlossen)".

**Verifikation:** Kein Code-Bezug nötig — die inhaltliche Deckung ist im PR-Body von #865 (Issue #843) explizit dokumentiert.

**Themen:** testinfrastruktur, backend, not-planned
