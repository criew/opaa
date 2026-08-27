# Issue #192 — chore(frontend): drop openapi-typescript peer override once upstream supports TypeScript 6
- Geschlossen: 2026-08-24 (not planned)
- Labels: frontend, size:S
- PRs: keine

**Laut Issue:** PR #191 hob TypeScript auf 6.0.3 an; `openapi-typescript@7.13.0` deklariert weiterhin nur `typescript: ^5.x` als Peer, weshalb ein `overrides`-Eintrag nötig wurde. Gefordert war, den Override zu entfernen, sobald eine Upstream-Version einen TypeScript-6-kompatiblen Peer-Bereich deklariert.

**Geliefert:** Nicht umgesetzt, weil die Voraussetzung weiterhin fehlt. Beim Schließen (24.08.2026) geprüft: `openapi-typescript@7.13.0` (weiterhin latest) deklariert unverändert `typescript: ^5.x`. Der Workaround selbst hat sich seit der pnpm-Migration (#653) geändert — er lebt nicht mehr als npm-`overrides`, sondern als `peerDependencyRules.allowedVersions` in `frontend/pnpm-workspace.yaml`. Geschlossen mit Verweis auf #751 (Renovate): Sobald Renovate ein Upstream-Release mit erweiterter Peer-Range sichtbar macht, kann die Regel im Update-PR entfernt werden — ein eigenes Tracking-Issue dafür gilt als überflüssig.

**Verifikation:** `frontend/pnpm-workspace.yaml` enthält weiterhin `peerDependencyRules.allowedVersions: 'openapi-typescript>typescript': '>=6.0.0'` — bestätigt, dass der Workaround unverändert aktiv ist.

**Themen:** frontend, projektsetup, ci
