# Issue #257 — docs: Einheitliche Testkonto-Konvention dokumentieren
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:S, security
- PRs: #689 (2026-08-21)

**Laut Issue:** Im Repository existierten mehrere, nicht abgestimmte Testkonto-Muster (Keycloak-Realm-Export, `.env.example`-Basic-Auth-Werte, E2E-Suite-Zugangsdaten), ohne zentrale Dokumentation, welches Konto wofür gilt. Gefordert war eine zentrale Übersicht, die Geltungsbereiche klärt und begründet, warum (nicht) vereinheitlicht wird.

**Geliefert:** Neuer Abschnitt „Testkonten im Überblick" in `docs/deployment.md`, verlinkt von `.env.example` und `e2e/README.md`. Der PR deckte bei der Bestandsaufnahme zwei Abweichungen vom Issue-Text auf: Ein eigenständiges `OPAA_AUTH_BASIC_USERNAME`/`_PASSWORD`-Paar existierte im Code zum Zeitpunkt des PRs bereits nicht mehr (der Mechanismus, den das Issue beschrieb, war zwischenzeitlich durch andere Arbeit überholt), und die E2E-Suite nutzt keine eigenen `e2e-user`/`e2e-password`-Zugangsdaten mehr, sondern die `dev`-Profil-Nutzer `dev-admin`/`dev-user`/`dev-outsider`. Der im Issue vorgeschlagene Verlinkungspunkt „Kommentar in `keycloak/realm-export.json`" entfiel bewusst (JSON kennt keine Kommentare); der Keycloak-Nutzer ist stattdessen direkt in der neuen Tabelle geführt. Eine Nachbesserung nach Review präzisierte zusätzlich den Umgang mit einer möglichen alten, gitignorten lokalen `.env.docker`, die noch alte Basic-Auth-Variablen enthalten könnte.

**Verifikation:** `docs/deployment.md` enthält den Abschnitt „Testkonten im Überblick" (Zeile 685 im aktuellen Worktree-Stand). `.env.example` enthält keine `OPAA_AUTH_BASIC_SECRET`/`OPAA_AUTH_MODE`-Variablen mehr — konsistent mit der im PR beschriebenen Beobachtung, dass dieser Mechanismus bereits entfallen war (siehe #260/#328).

**Themen:** doku, auth, testing, e2e
