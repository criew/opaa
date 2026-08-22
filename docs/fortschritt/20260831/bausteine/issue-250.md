# Issue #250 — docs(security): Härtungsanforderungen für erreichbare Compose-Deployments dokumentieren
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:M, security
- PRs: #714 (2026-08-21)

**Laut Issue:** Der mitgelieferte Compose-Stack ist für Entwicklung gebaut, wird aber laut Dokumentation nachgebaut und erreichbar betrieben. Vier belegte Vorgabewerte sollten mit Fundstelle, Risiko und Gegenmaßnahme dokumentiert werden: vorkonfigurierter Realm-Benutzer `testuser`/`testpass`, `sslRequired: none`, Keycloak-Bootstrap-Admin `admin`/`admin`, veröffentlichter PostgreSQL-Host-Port. Zusätzlich: Ersetzen von DB-Zugangsdaten und `OPAA_AUTH_BASIC_SECRET`, Hinweis dass `mock`-Auth nie erreichbar betrieben wird, Querverweis aus `docker-compose.yml`, Prüfung ob eine separate `docker-compose.prod.yml` sinnvoller wäre.

**Geliefert:** Abschnitt "Härtung für erreichbare Deployments" in `docs/deployment.md` mit den vier Punkten, je mit Fundstelle/Risiko/Gegenmaßnahme und Kennzeichnung "zwingend" vs. "empfohlen". Warnhinweis in `docker-compose.yml` verweist jetzt auf den Abschnitt. Abweichung vom Issue: `OPAA_AUTH_BASIC_SECRET` existiert im Repository nicht mehr (mit Commit `fd04246`, PR #328/#255 entfernt) — dokumentiert ist stattdessen, dass OPAA kein eigenes JWT-Signier-Geheimnis mehr hat und der einzige ungeprüfte Auth-Weg das Spring-Profil `dev` ist. Empfehlung zur offenen Frage (separate Compose-Datei vs. Textliste): Textliste, mit Begründung im Dokument. Der Punkt "Hinweis, dass `mock` nie erreichbar betrieben wird" ist gegenstandslos geworden, da der `mock`-Modus selbst mit #255/PR #328 entfernt wurde.

**Verifikation:** `docs/deployment.md` enthält den Abschnitt "Härtung für erreichbare Deployments" (Zeile ~196), `docker-compose.yml` verweist im Warnkopf darauf. Der Abschnitt ist seither erheblich gewachsen (u. a. um die Rheinfurt-Demo-Konten und den `opaa-seed`-Client aus #712) — die ursprünglichen vier Punkte sind weiterhin enthalten, ergänzt um neue.

**Themen:** security, deployment, doku
