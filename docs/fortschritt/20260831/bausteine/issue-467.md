# Issue #467 — feat(indexing): RSS-Feeds indizieren — Einträge auflösen und Detailseiten übernehmen
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:L
- PRs: #490 (2026-08-18)

**Laut Issue:** Phase 2 (Lauf) — Feed abrufen, Änderungserkennung (Feed via ETag/If-Modified-Since, Eintrag via `pubDate`/`last_modified_remote`, Inhalt via Prüfsumme), Haupttext der Detailseite gewinnen statt ganzer Seite, kein Löschabgleich bei verschwundenen Einträgen (bewusste Ausnahme vom sonstigen Verhalten), robustes Verhalten gegenüber fremden Zielen: einzelner Fehlschlag darf Lauf nicht stoppen, konfigurierbarer Abstand zwischen Abrufen, konfigurierbarer wahrheitsgemäßer User-Agent, Obergrenzen für Anzahl/Größe. Abweisung durch die Gegenstelle muss von Verarbeitungsfehlern unterscheidbar bleiben.

**Geliefert:** Wie gefordert — `RssFeedIndexingExecutor`, neue Tabelle `rss_feed_state` (Migration 025) für ETag/Last-Modified, Änderungsprüfung je Eintrag vor Abruf der Detailseite, Haupttext-Gewinnung über Jsoup mit Entfernen von `nav`/`header`/`footer` und konfigurierbarem CSS-Selektor. Zusätzliche, im Issue nur implizit geforderte Sicherheits-/Robustheitsauflagen aus dem Review von PR #474 wurden mit umgesetzt: Obergrenzen während des Streamens durchgesetzt (nicht erst nach vollständigem Laden), Schema-Prüfung der Links (nur http/https). Konfiguration unter `opaa.indexing.rss.*`, dokumentiert in `.env.example` und `docs/deployment.md`. Kein Löschabgleich wie gefordert. Kein Reproduktionsnachweis, da neues Feature statt Bugfix — sachlich korrekt, keine Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedIndexingExecutor.java` existiert im heutigen Stand des Worktrees; `docs/deployment.md` und `.env.example` laut Dateiliste ebenfalls angepasst.

**Themen:** indexing, rss, executor, robustheit, rate-limiting, backend
