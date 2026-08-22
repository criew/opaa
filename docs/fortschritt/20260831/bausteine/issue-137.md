# Issue #137 — perf(auth): avoid DB round-trip on every request in UserProvisioningFilter
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine

**Laut Issue:** `UserProvisioningFilter` ruft bei jedem authentifizierten Request `UserService.findOrCreateUser(...)` auf und erzeugt damit einen DB-Roundtrip je Request. Vorgeschlagen: Kurzlebiger Cache (z. B. Caffeine) oder Verlagerung der Provisionierung, plus eine Update-Policy für `lastLoginAt`.

**Geliefert:** Nichts im Sinne dieses Issues direkt — geschlossen ohne PR, weil der Befund laut Schließungskommentar in #307 aufgegangen ist. Dort wird derselbe Codepfad im Zusammenhang mit einem Connection-Pool-Befund ohnehin analysiert (wie viele Connections ein Login-Request hält, ob Provisionierung aus dem Request-Pfad gelöst wird); die hier vorgeschlagenen Lösungsansätze (Caffeine-Cache, `lastLoginAt`-Intervall) sollen dort mitbewertet werden, um dieselbe Stelle nicht doppelt anzufassen.

**Verifikation:** Keine eigenständige Prüfung von `UserProvisioningFilter` vorgenommen, da laut Schließungskommentar die Behebung planmäßig in #307 verortet ist und nicht Teil dieses Vorgangs war.

**Themen:** auth, performance, connection-pool, followup
