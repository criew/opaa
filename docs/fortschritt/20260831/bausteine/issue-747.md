# Issue #747 — feat(api): Content-Endpunkt streamt Remote-Originale serverseitig durch (Proxy)
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #748 (2026-08-22)

**Laut Issue:** Folgebefund aus Epic #740: Für `HTTP_DIRECTORY`-/`RSS_FEED`-Dokumente verlinkten die Deeplinks die beim Indizieren gespeicherte Quell-URL — bei nur intern erreichbaren Quellsystemen (z. B. der Demo-Korpus-Container) läuft der Link im Browser ins Leere, was in Behörden mit internen Fileservern der Regelfall ist. Maintainer-Entscheidung: Der Content-Endpunkt soll Remote-Originale serverseitig durchstreamen (Proxy) statt auf die Quelle zu verweisen, mit SSRF-Begrenzung, Credential-Weitergabe zur Quelle (nie zum Client) und 404 bei Nichterreichbarkeit.

**Geliefert:** Wie gefordert. `GET /api/v1/documents/{documentId}/content` streamt für `HTTP_DIRECTORY`/`RSS_FEED` jetzt serverseitig von der gespeicherten Quell-URL, unter Wiederverwendung bestehender Indexer-Infrastruktur (`UrlFileDownloader#downloadBounded` mit Ziel-Allowlist-Doppelprüfung und hostgebundenen Redirects, `AutoindexCrawlerService`-Auth-Helfer, `UploadProperties#maxFileSize` als Größenbegrenzung). `DocumentContent` trägt ein `temporary`-Flag; das heruntergeladene Temp-File wird nach dem Streamen gelöscht. Frontend: alle drei Stellen (`SourceFootnotes`, `SourceEvidenceDrawer`, `LibraryDetailPage`) öffnen jetzt jeden Quellentyp über den Content-Endpunkt; die Quell-URL bleibt als sekundäre Info (Tooltip/eigene Zeile) sichtbar. Bewusste Annahme: kein zusätzlicher konfigurierbarer User-Agent für den Proxy-Abruf, Größenbegrenzung über die bestehende Upload-Grenze statt neuer Konfiguration.

**Verifikation:** Nicht erneut im Code geprüft — PR-Beschreibung dokumentiert umfangreiche Test-Abdeckung (Unit- und Integrationstests für Proxy-Erfolg, Credentials, Offline-Quelle, Allowlist-Ablehnung).

**Themen:** retrieval, security, backend, frontend, deeplinks
