# Issue #843 — test(backend): Test-Kontexte inventarisieren und auf kanonische Meta-Annotationen konsolidieren
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: #865 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 2 (Befund T3), erweitert um den Umfang von #844. 41 `@SpringBootTest`-Klassen mit ~15 unterschiedlichen Kontext-Konfigurationen — jede ein Kontext-Cache-Miss mit eigenem Testcontainers-Postgres. Inventar, ≤3 kanonische Signaturen, Umstellung aller Klassen, AGENTS.md-Konvention.

**Geliefert:** 44 `@SpringBootTest`-Klassen inventarisiert (20 unterschiedliche Signaturen), daraus zwei kanonische Meta-Annotationen abgeleitet (`@OpaaIntegrationTest`, `@OpaaMockMvcTest`, Paket `io.opaa.test`), 41 Klassen umgestellt, 3 begründete Ausnahmen (`MixedProviderConfigurationTest`, `ProviderConfigurationTest`, `OpenAiIntegrationTest`). AGENTS.md um Abschnitt „Spring-Testkontexte" ergänzt. Ehrlich dokumentierter Trade-off: die Kontextzahl sank NICHT (19 vor/nach), und der gemessene Container-Peak stieg sogar von ~16 auf 21, weil Spring-Kontext-Caching Container länger offenhält als vormals klassen-gebundene `@Container`-Felder. Der Nutzen liegt laut PR ausschließlich in der Annotationsoberfläche (von 41 Ad-hoc-Signaturen auf 2 benannte plus 3 Ausnahmen) und im Entfernen echter Code-Duplikation. Migrations-Fixture-Ketten wurden nur bewertet, nicht umgebaut (Folgeticket vorgeschlagen).

**Verifikation:** `backend/src/test/java/io/opaa/test/OpaaIntegrationTest.java` und `OpaaMockMvcTest.java` im Worktree vorhanden; AGENTS.md enthält den Abschnitt „Spring-Testkontexte" mit den beiden Meta-Annotationen.

**Themen:** testinfrastruktur, backend, spring, technische-schulden
