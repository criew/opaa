# Issue #73 — 🔵 [LOW] Inconsistent Mock Profile Naming
- Geschlossen: 2026-08-14 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine

**Laut Issue:** Das Spring-Profil `mock` war irreführend benannt — es schaltete tatsächlich die Datenbank ab, aktivierte aber keine Mocks; Controller nutzten die doppelte Verneinung `@Profile("!mock")`. Gefordert: konsistente Umbenennung (z. B. `no-db`/`standalone`) durchgängig in Code, Docs, Docker-Compose.

**Geliefert:** Nicht umgesetzt wie vorgeschlagen — laut Schließkommentar (criew, 14.08.2026) existiert der kritisierte Zustand schlicht nicht mehr. Das `mock`-Profil mit Datenbank-Ausschluss war zum Zeitpunkt der Prüfung bereits aus `application.yml` verschwunden. Mit der Einführung von Space-Konzept und Auth-Umbau (#328, Entscheidung #323) heißt der Entwicklungsmodus jetzt `dev` und beschreibt treffend, was er tut (Authentifizierung ohne Anmeldedaten-Prüfung). Sämtliche `@Profile`-Bedingungen an Controllern und `UserService` sind entfallen — es gibt gar keine Profilverzweigung mehr in der Fachschicht. Das Ziel des Issues (Klarheit) wurde also erreicht, aber nicht durch Umbenennung, sondern durch ersatzlose Entfernung der Verzweigung.

**Verifikation:** Kein `mock`-Profil und keine `@Profile`-Annotationen mehr in Controllern/`UserService` im heutigen Worktree feststellbar (laut Schließkommentar; nicht erneut tief geprüft, da NOT_PLANNED mit klarer Begründung).

**Themen:** backend, profile, konfiguration, not-planned
