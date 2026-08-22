# Issue #716 — fix(deployment): Schnellstart-Kopie von .env.example ergibt keinen startfähigen Compose-Stack
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, documentation
- PRs: #719 (2026-08-21)

**Laut Issue:** Der dokumentierte Schnellstart `cp .env.example .env.docker` führte zu keinem lauffähigen Stack: fehlendes `SPRING_PROFILES_ACTIVE` (Abbruch durch `AuthProfileGuard`), falsches `OPAA_DB_URL` (`localhost` statt Container-Hostname), falsche CORS-Origin (Port 5173 statt 3000). Lösung offen (angepasste `.env.example` oder getrennte Vorlagen), im PR zu begründen.

**Geliefert:** Deckungsgleich — Entscheidung für getrennte Vorlagen: `.env.example` bleibt die `bootRun`-Vorlage, neue `.env.docker.example` ist die Compose-Vorlage mit `SPRING_PROFILES_ACTIVE=docker,dev`, auskommentiertem `OPAA_DB_URL`, korrekter CORS-Origin. `docs/deployment.md` und `.gitignore` (Ausnahme `!.env.docker.example`) nachgezogen. Nachweis: frischer `cp` + `docker compose up` in isoliertem Projekt, Backend startet, CORS und dev-Login funktionieren.

**Verifikation:** `.env.docker.example` existiert im Worktree.

**Themen:** deployment, doku, docker, konfiguration
