# Issue #323 — Auth-Konzept reviewen: Werden mock- und basic-Modus noch gebraucht?
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, backend, frontend, size:M, security, auth
- PRs: #328 (2026-08-14)

**Laut Issue:** Kein Umsetzungsauftrag, sondern ein Entscheidungsauftrag. Codeanalyse zeigt: `mock` ist kein echter Auth-Modus, sondern eine Konfigurationslücke, die die Anwendung unbenutzbar macht (broken closed). `basic` hat mehrere Mängel (nicht-portable Identitäten bei Umstieg auf oidc, HMAC-Secret in Betriebskonfiguration, kein konstantzeitiger Passwortvergleich, kein Rate-Limiting, nur ein konfigurierbarer Nutzer). Gefordert: erhobene Szenarien, Aufwandsschätzung, Bewertung von drei Varianten, eine ADR-Entscheidung, Folge-Issues für die Umsetzung.

**Geliefert:** Der PR #328 geht über eine reine Entscheidung hinaus und setzt direkt Variante 2 („oidc + echter Entwicklungsmodus") um — `mock` und `basic` werden vollständig entfernt, `DevSecurityConfig`/`DevAuthFilter` treten an ihre Stelle (synthetisches JWT via `X-OPAA-Dev-User`-Header bzw. `?devUser=`), zwei vorkonfigurierte Nutzer (`dev-admin`, `dev-user`), `AuthProfileGuard` verweigert den Start ohne Auth-Profil, alle `@Profile`-Annotationen an Controllern/UserService entfallen, Login-Endpunkt und -Formular sind entfernt. ADR-0005 wurde vollständig neu geschrieben statt per Nachfolge-ADR ersetzt. Der PR schließt zugleich #323, #255 und #260 in einem Schritt — die im Issue verlangten separaten Folge-Issues wurden nicht einzeln angelegt, sondern direkt mitimplementiert; #138 wird gegenstandslos, #139/#73 werden als vermutlich hinfällig benannt, aber nicht selbst entschieden.

**Verifikation:** `backend/src/main/java/io/opaa/auth/DevAuthFilter.java` und `DevSecurityConfig.java` existieren im heutigen Code; `AGENTS.md` verlangt `SPRING_PROFILES_ACTIVE=local,dev` als Startbefehl. Der Umbau ist im Code sichtbar konsequent umgesetzt.

**Themen:** auth, security, backend, adr
