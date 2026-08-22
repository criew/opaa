# Issue #360 — docs(features): Wissensschicht, Wissensquellen und Modellsteuerung neu fassen (A, B, E)
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #368 (2026-08-14)

**Laut Issue:** Teil von #340, Bündel 1 der Feature-Spezifikationen. `data-indexing-rag.md` überarbeiten (Zitierzwang, Konfidenz, hybride Suche, Deep Document Understanding, Wissensgraph als Ausbaustufe), `knowledge-sources.md` neu anlegen (Upload/Konnektor, Rechte-Spiegelung, Lebenszyklus), `llm-integration.md` umschreiben (Modellverwaltung statt fest verdrahteter Anbieter, lokal-first, zentrale Vorgaben als Obergrenze). Abnahmekriterien: TEMPLATE-Konformität, Phasenlage, keine Anbieternamen/Preise, Vektorspeicher-Frage bleibt offen (#348).

**Geliefert:** Wie beschrieben. `data-indexing-rag.md` überarbeitet (463 Zeilen), `knowledge-sources.md` neu (415 Zeilen), `llm-integration.md` neu geschrieben (393 Zeilen). Alle Grenzen eingehalten (keine Mitbewerber, Preise, Referenzkunden). Vektorspeicherfrage ausdrücklich an #348 verwiesen statt entschieden. Deckt sich mit dem Issue-Umfang, keine Abweichung erkennbar.

**Verifikation:** Alle drei Dateien existieren im Worktree unter `docs/features/`.

**Themen:** produktausrichtung, doku, retrieval, knowledge-sources, llm-integration, spec
