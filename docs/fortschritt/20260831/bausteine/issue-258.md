# Issue #258 — docs: Beispiel-Secret für OPAA_AUTH_BASIC_SECRET verhindert Backend-Start
- Geschlossen: 2026-08-14 (not planned)
- Labels: bug, documentation, size:S
- PRs: keine

**Laut Issue:** `.env.example` und `docs/deployment.md` empfahlen als Beispielwert für `OPAA_AUTH_BASIC_SECRET` exakt den String, den `BasicSecurityConfig.validateBasicAuthConfiguration()` beim Start des `basic`-Profils hart als `INSECURE_DEFAULT_SECRET` ablehnte. Wer die Dokumentation kopierte und das `basic`-Profil aktivierte, bekam einen nicht startenden Container, ohne dass die Doku das erwähnte.

**Geliefert:** Nichts direkt zu diesem Issue — als „not planned" ohne verknüpften PR geschlossen. Laut Maintainer-Kommentar ist das Issue durch PR #328 (Issue #260, „Auth-Modi auf oidc und dev reduzieren") hinfällig geworden: Der `basic`-Modus wurde dort ersatzlos entfernt, samt `BasicSecurityConfig` und der darin hinterlegten Ablehnung des Beispielwerts. `OPAA_AUTH_BASIC_SECRET` existiert seitdem nicht mehr, die im Issue beschriebene Falle ist damit strukturell beseitigt, nicht durch eine gezielte Doku-Korrektur.

**Verifikation:** `backend/src/main/java/io/opaa/auth/` enthält keine `BasicSecurityConfig.java` mehr; `.env.example` enthält keine `OPAA_AUTH_BASIC_SECRET`- oder `OPAA_AUTH_MODE`-Variablen. Bestätigt: Der Fehlerpfad aus dem Issue kann heute nicht mehr auftreten, weil der ganze `basic`-Modus weg ist.

**Themen:** auth, doku, projektsetup
