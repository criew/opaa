# Issue #260 — feat(auth): mehrere opaa.auth.basic.users-Einträge konfigurierbar machen
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Das `basic`-Auth-Profil unterstützte nur genau einen konfigurierten Nutzer (`opaa.auth.basic.users` mit einem Eintrag aus `OPAA_AUTH_BASIC_USERNAME`/`_PASSWORD`). Gefordert war, mehrere Nutzer konfigurierbar zu machen, damit z. B. Szenario 5 aus #232 (Ablehnung eines nicht-privilegierten Nutzers) mit einem zweiten Testnutzer geprüft werden kann.

**Geliefert:** Deutliche Abweichung vom Issue-Umfang, im PR selbst offen benannt: Statt `opaa.auth.basic.users` um mehrere Einträge zu erweitern, wurde der gesamte `basic`-Auth-Modus ersatzlos entfernt (PR #328, „Auth-Modi auf oidc und dev reduzieren", setzt die Grundsatzentscheidung aus #323 um). An seine Stelle tritt ein `dev`-Modus mit `DevSecurityConfig`/`DevAuthFilter`, der zwei vorkonfigurierte Nutzer (`dev-admin`, `dev-user`) über den Header `X-OPAA-Dev-User` bzw. den Query-Parameter `?devUser=` auswählbar macht. Damit ist das eigentliche Bedürfnis (mehrere Testnutzer mit unterschiedlichen Rollen) erfüllt, aber nicht durch die im Issue skizzierte Lösung — der PR macht #260 laut eigener Beschreibung „gegenstandslos" und schließt es trotzdem als erledigt, weil das dahinterliegende Ziel erreicht ist. Der PR schließt zugleich #323 und #255 mit.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevSecurityConfig.java` und `DevAuthFilter.java` existieren im aktuellen Stand; `BasicSecurityConfig.java` und `AuthProperties.BasicAuth`/`BasicUser` existieren nicht mehr. `application.yml` enthält keinen `opaa.auth.basic.users`-Block mehr.

**Themen:** auth, backend, e2e, testing
