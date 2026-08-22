# Issue #713 — docs(demo): Installationsanleitung, Nutzerkonten und Drehbuch der Demo-Instanz
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:M, demo
- PRs: #727 (2026-08-21)

**Laut Issue:** Anwenderdokumentation für die Rheinfurt-Demo: Installation mit einem Befehl, Nutzerkonten-Tabelle mit Rollen/Spaces/lesbaren Bibliotheken, ausformuliertes Drehbuch mit den acht Konzeptfragen (inkl. Berechtigungs-Doppelfrage und bewusst unbeantwortbarer Frage), Ablauf zur Korpus-Aktualisierung, Verweis von `search-quality-evaluation.md` auf das neue Konzept.

**Geliefert:** Deckungsgleich — neue Seite `docs/demo-walkthrough.md`, verlinkt aus `README.md`, `demo/README.md` und `docs/features/demo-instance.md` statt dupliziert. Drei Drehbuchfragen (Berechtigungs-Doppelfrage, Quer-Bibliotheks-Frage, unbeantwortbare Frage) wurden gegen einen isolierten Compose-Stack mit `ai-stub` tatsächlich durchgespielt und im PR mit den API-Antworten belegt; die übrigen Fragen beruhen auf manueller Korpusprüfung, da `ai-stub` inhaltliche Relevanz nicht sinnvoll misst (dokumentierte Einschränkung, keine verschwiegene Lücke).

**Verifikation:** `docs/demo-walkthrough.md` existiert im Worktree.

**Themen:** demo, doku, drehbuch, installation
