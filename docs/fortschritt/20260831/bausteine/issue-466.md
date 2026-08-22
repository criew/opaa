# Issue #466 — feat(indexing): RSS_FEED als Quellentyp und Parser für RSS-2.0-Feeds
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #474 (2026-08-18)

**Laut Issue:** Phase 2 (Gerüst) — `RSS_FEED` als dritter Wert von `DocumentSourceType` samt Liquibase-Migration der `CHECK`-Constraint, plus ein von Netz und Datenbank unabhängig prüfbarer RSS-2.0-Parser mit XXE-Härtung, robustem Datumsparsing (mehrere `pubDate`-Schreibweisen), Nachsicht gegenüber fremden Namensräumen/Elementen und deutscher Fehlermeldung bei nicht lesbarem XML. Testdaten ausdrücklich erfunden/generisch, kein Bezug zu realen Adressen.

**Geliefert:** Wie gefordert — `RSS_FEED` in Java-Enum, OpenAPI-Schema und Migration `024-allow-rss-feed-source-type.yaml` (nach dem Muster von 020, Constraint droppen und neu anlegen statt vorhandenes changeSet zu bearbeiten). `RssFeedParser` mit JDK-StAX, XXE-Gegenmaßnahmen (`SUPPORT_DTD`/`IS_SUPPORTING_EXTERNAL_ENTITIES` aus), Fallback-Datumsformate und Zeitzonen-Normalisierung, Nachsicht gegenüber fremden Namensräumen. Neun Testfälle in `RssFeedParserTest`, Fixtures unter `backend/src/test/resources/rss-feeds/` mit `example.invalid`-Domänen. Migrationstest lokal ohne Docker übersprungen, laut PR in CI mit Docker geprüft. Keine Abweichung vom Issue; noch kein Indizierungslauf (bewusst außerhalb des Umfangs, folgt in #467).

**Verifikation:** `backend/src/main/java/io/opaa/indexing/RssFeedParser.java`, `db.changelog/changes/024-allow-rss-feed-source-type.yaml` und die Testfixtures existieren im heutigen Stand des Worktrees.

**Themen:** indexing, rss, parser, sicherheit, xxe, migration, backend
