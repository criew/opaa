# Issue #307 — fix(auth): Gleichzeitige Erstanmeldungen verschiedener Nutzer erschöpfen den Connection-Pool
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:M, auth
- PRs: #702 (2026-08-21)

**Laut Issue:** Bei 12 gleichzeitigen Erstanmeldungen verschiedener Nutzer scheitern Requests bei Standard-Poolgröße 10 nach 30s mit „Connection is not available", obwohl die Datenbank idle ist — Ursache unklar, Login meldet trotzdem Erfolg, während Space/Bibliothek fehlen. Gefordert: Ursachenklärung, Fix ohne bloße Symptombehandlung (Poolgröße), Reproduktionstest mit echten Threads gegen Produktions-Poolgröße.

**Geliefert:** PR #702 klärt die Ursache empirisch (jstack + `pg_stat_activity` während des Hängers): kein Deadlock, sondern reine Pool-Warteschlangen-Kontention — ein Erstlogin braucht vier sequenzielle Pool-Zyklen, bei 12 gleichzeitigen Logins bis zu 48 Borrow/Return-Zyklen bei nur 10 Connections. Fix entlastet die Provisionierung statt die Poolgröße zu erhöhen: `SpaceService.ensureDefaultSpaceForNewUser` überspringt die redundante `existsBy`-Prüfung bei tatsächlich neu angelegten Nutzern, ein Caffeine-Cache merkt sich provisionierte Spaces je Nutzer, ein neuer `AuthMetrics`-Counter macht fehlgeschlagene Provisionierung sichtbar (offene Frage aus #294). `application.yml` dokumentiert die bewusst unveränderte Poolgröße 10 explizit. Abweichung vom ursprünglichen Abnahmekriterium „... und persönliche Bibliothek": laut PR seit #522/#546 gegenstandslos, da automatische Bibliotheks-Provisionierung beim Login inzwischen entfernt wurde.

**Verifikation:** `AuthMetrics.java` und `UserServiceConcurrentDistinctUserLoginIntegrationTest.java` existieren im Worktree.

**Themen:** auth, backend, connection-pool, concurrency, spaces
