# Issue #119 — feat(library): Speicherkontingent je Bibliothek und Organisation
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #700 (2026-08-21)

**Laut Issue:** Ein technisch durchgesetztes Speicherkontingent je Konto/Bibliothek sowie eine datenschutzkonforme Dublettenerkennung beim Aufnehmen von Dokumenten in eine Wissensbibliothek — mit striktem Verbot, Treffer aus nicht-lesbaren Bibliotheken preiszugeben.

**Geliefert:** Nur das Speicherkontingent, und zwar **je Bibliothek**, nicht je Konto/Organisation. PR #700 führt `LibraryStorageQuotaService` mit konfigurierbarem Kontingent (`opaa.upload.library-quota-bytes`, Default 10 GiB) ein, durchgesetzt an allen Aufnahmepfaden (Upload, Filesystem/HTTP/RSS-Konnektoren) mit 413-Ablehnung bzw. `QUOTA_EXCEEDED`-Skip im Laufprotokoll. Laut PR-Beschreibung ist der Dublettenteil bereits durch eine bestehende Checksum-Dublettensperre (Migration 020, PR zu #420) abgedeckt und laut Zuschnitts-Kommentar nicht mehr Teil dieses Tickets. Das Organisations-Gesamtkontingent wurde bewusst nicht umgesetzt und im Issue als „Offen" vermerkt — der ursprüngliche Umfang (Kontingent je Konto plus Dublettenwarnung mit Datenschutzgarantien) ist damit deutlich reduziert.

**Verifikation:** `LibraryStorageQuotaService.java` existiert im Worktree unter `backend/src/main/java/io/opaa/library/`.

**Themen:** wissensbibliothek, speicherkontingent, governance, upload
