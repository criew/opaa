# Issue #139 — feat(auth): add basic-profile user management for system admins
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:M, auth
- PRs: keine

**Laut Issue:** Persistente Verwaltung von Basic-Auth-Nutzern (statt YAML-Konfiguration) mit UI für System-Admins zum Anlegen/Löschen, passwortgehasht (BCrypt).

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen. Ein früher Kommentar (07.03.2026) merkte an, dass zusätzlich verhindert werden müsse, dass sich der letzte System-Admin selbst demoten kann. Der finale Schließungskommentar (14.08.2026) erklärt das Issue für hinfällig, weil das `basic`-Profil mit #328 (Entscheidung #323) entfallen ist — Nutzer kommen im Betrieb ausschließlich aus dem OIDC-Anbieter, Anmeldedaten werden dort verwaltet. Der dahinterliegende Wunsch nach UI-Rollenverwaltung besteht laut Kommentar für OIDC-Betrieb teilweise fort: `AdminController` (`listUsers`, `changeRole`) kann das bereits; offene Punkte dazu laufen unter #271. Weitergehender Bedarf an UI-Nutzerverwaltung müsste als neues, entkoppeltes Issue formuliert werden.

**Verifikation:** Kein `basic`-Profil und keine Basic-Auth-Nutzerverwaltung im heutigen Code; `AdminController` mit Rollenverwaltung ist vorhanden (nicht tiefer geprüft, da Primärquelle Schließungskommentar).

**Themen:** auth, admin, verworfen, oidc
