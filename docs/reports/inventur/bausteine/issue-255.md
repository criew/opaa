# Issue #255 — fix(auth): mock-Modus funktionsfähig machen oder aus Default und Doku entfernen
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:M, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Der Auth-Modus `mock` war Default und dokumentiert, funktionierte im Backend aber nicht: Es gab nur profilgebundene `SecurityFilterChain`-Beans für `basic` und `oidc`; ohne eines dieser Profile blockte Spring Boots generische Security-Autokonfiguration alle Anfragen (statt sie freizugeben, wie `mock` versprach), und die Fach-Controller existierten mangels passendem Profil gar nicht als Beans. Vorgeschlagen wurden zwei Optionen: `mock` funktionsfähig machen, oder ihn aus Default und Doku entfernen.

**Geliefert:** Deutlich weiter gefasst als beide Issue-Optionen — PR #328 setzt eine zwischenzeitliche Entscheidung (#323) um und reduziert die Auth-Modi grundsätzlich auf `oidc` und `dev`. `mock` **und** `basic` entfallen ersatzlos (nicht nur `mock`, wie im Issue erwogen); `basic` wurde zusätzlich wegen inhaltlicher Mängel verworfen (Identitätswechsel bei Umstieg auf `oidc`, HMAC-Secret in der Betriebskonfiguration, kein konstanter Passwortvergleich, kein Rate-Limiting, nur ein konfigurierbarer Nutzer). An die Stelle von `mock` tritt `DevSecurityConfig`/`DevAuthFilter`: ein synthetisches JWT für einen konfigurierten Nutzer (`dev-admin`, `dev-user`), Auswahl per Header/Query-Parameter, 401 bei unbekanntem Nutzer statt stillem Rückfall. `AuthProfileGuard` bricht den Start ohne aktives Auth-Profil hart ab. `POST /api/v1/auth/login` und das Anmeldeformular entfallen vollständig — es gibt keinen passwortbasierten Anmeldeweg mehr. PR schließt zugleich #323 und #260 und macht #138 (Rate-Limiting am Login) gegenstandslos.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevSecurityConfig.java`, `DevAuthFilter.java` und `AuthProfileGuard.java` existieren im heutigen Code; `BasicSecurityConfig.java` existiert nicht mehr — Entfernung bestätigt, konsistent mit dem PR-Umfang.

**Themen:** auth, security, backend, e2e
