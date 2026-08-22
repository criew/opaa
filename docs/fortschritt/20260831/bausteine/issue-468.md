# Issue #468 — feat(indexing): Anlagen an Detailseiten übernehmen, mit Profil für den Government Site Builder
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #492 (2026-08-18)

**Laut Issue:** Phase 3 — Profilbegriff für „welche Verweise einer Detailseite sind Anlagen", ein allgemeines Profil und eines für den Government Site Builder (Anhänge über Abfrageparameter statt Dateiendung), Profilwahl je Lauf mit allgemeinem Profil als Voreinstellung (keine automatische CMS-Erkennung), Herkunft der Anlage zum Eintrag nachvollziehbar, Deduplizierung gleicher Anlagen über mehrere Einträge, Obergrenzen. Nebenbefund: `jsoup` war nur transitiv vorhanden und sollte ordentlich in den Versionskatalog aufgenommen werden.

**Geliefert:** `AttachmentProfile` mit `GENERIC` (Endungen aus `SupportedDocumentFormats`, gleicher Host) und `GSB` (erkennt `__blob=publicationFile`-Muster, leitet Dateinamen aus Pfadsegment/Content-Type her). Neue Spalte `documents.source_entry_url` (Migration 026) für Herkunftsnachweis, Deduplizierung über bestehende `findByFilePath`-Logik. `jsoup` jetzt in `libs.versions.toml` deklariert. **Ausdrücklich benannte Abweichung vom Ticket-Wortlaut:** Profilwahl ist als Application-Property (`opaa.indexing.rss.attachment-profile`, Default `GENERIC`) umgesetzt statt als Request-Feld je Lauf, weil Epic #486/ADR-0018 die dauerhafte Quellkonfiguration ohnehin von `IndexingTriggerRequest` auf die Wissensbibliothek verlagert — ein Request-Feld wäre laut PR Wegwerfarbeit gewesen. Damit ist die Abnahme „Profilwahl je Lauf" so nicht erfüllt, sondern durch eine globale Konfiguration ersetzt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/AttachmentProfile.java` existiert im heutigen Stand; `libs.versions.toml`-Eintrag für jsoup nicht einzeln nachgeprüft.

**Themen:** indexing, rss, anlagen, gsb, konfiguration, backend
