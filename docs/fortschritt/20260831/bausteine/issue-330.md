# Issue #330 — Rechtemodell verschlanken: Asset-Rolle USER und Gruppenrollen streichen
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:S, security
- PRs: #331 (2026-08-14)

**Laut Issue:** Zwei Streichungen im Rechtemodell: (1) Asset-Rolle `USER` entfällt, da bei Agenten nicht durchsetzbar und bei Bibliotheken wirkungslos, zudem bereits im Produktivcode tot; `VIEWER` wird unterste Stufe. (2) Gruppenrollen (`STEWARD`, `LEAD`) und die gesamte Annahmeseite einer Freigabe entfallen — ein Grant an eine Gruppe braucht keine Zustimmung mehr. Verlangt: Enum-Änderung, Liquibase-Migration (bestehende `USER`-Grants auf `VIEWER` heben, CHECK-Constraint verengen), Doku-Anpassung, Migrationstest.

**Geliefert:** PR #331 setzt beides um: `AssetRole` ohne `USER`, Migration `014-drop-asset-role-user.yaml` mit zwei zwingend geordneten changeSets (erst Promotion auf VIEWER, dann Constraint-Verengung), `Migration014DropAssetRoleUserTest`. Reproduktionsnachweis mit temporärem No-op-Changeset dokumentiert (zwei Tests schlagen dabei erwartungsgemäß fehl). Doku in `access-control.md` und `spaces-and-assets.md` angepasst, verworfene Alternativen festgehalten. Schließt zusätzlich #208 gegenstandslos ab; ist Teil von Epic #198. Deckt sich mit dem Issue.

**Verifikation:** `AssetRole.java` im Worktree beginnt mit `VIEWER` als erstem Enum-Wert, Javadoc erwähnt die frühere `USER`-Stufe nur noch historisch. Bestätigt.

**Themen:** backend, security, spaces, rechtemodell, migration
