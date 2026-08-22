# Issue #332 — docs: Startbefehle nennen das verpflichtende Auth-Profil nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S, auth
- PRs: #334 (2026-08-14)

**Laut Issue:** Folgefehler aus #328/#323 — der in AGENTS.md dokumentierte Startbefehl `./gradlew bootRun` bricht seit Einführung des `AuthProfileGuard` ab, weil `SPRING_PROFILES_ACTIVE` kein Auth-Profil (`oidc`/`dev`) enthält. Betroffen: AGENTS.md (Build & Test) und `docs/MVP-VERIFICATION.md` (Schritt 2, beide Varianten). Gefordert: Startbefehle mit `SPRING_PROFILES_ACTIVE=local,dev` korrigieren, Grund mit ADR-0005-Verweis nennen, `?devUser=`-Hinweis beim Frontend-Dev-Server ergänzen.

**Geliefert:** PR #334 korrigiert beide Dokumente wie gefordert, inklusive Verifikationstabelle (Fehlschlag ohne Profil, Erfolg mit Profil, Funktionsprüfung `/api/v1/auth/config` und `/api/v1/auth/me` mit/ohne Dev-User-Header). Deckt sich vollständig mit dem Issue.

**Verifikation:** `AGENTS.md` enthält Zeile 53 `SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun`. Bestätigt.

**Themen:** doku, auth, projektsetup
