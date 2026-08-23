# Issue #737 — fix(auth): Plötzlicher Logout — Silent-Token-Renew und 401-Retry statt Sofort-Logout
- Geschlossen: 2026-08-22 (completed)
- Labels: bug, frontend, size:M, auth
- PRs: #741 (2026-08-22)

**Laut Issue:** Maintainer-Beobachtung auf der Demo-Instanz: zufälliges Ausloggen. Ursache: `authStore` hielt das Access-Token nur als einmaligen Snapshot, kein Handler übernahm die stille Erneuerung durch `oidc-client-ts`; nach Ablauf der Keycloak-Default-Lebensdauer (5 min) führte jeder 401 sofort zu `signoutRedirect()`, auch durch Hintergrund-Polling ausgelöst. Gefordert: `UserManager`-Events abonnieren, `automaticSilentRenew`, zweistufige 401-Behandlung (Silent-Renew-Versuch + Retry, erst dann lokaler Logout ohne IdP-Zerstörung), explizite Keycloak-Lebensdauern, Reproduktionsnachweis, ADR-0005-Update.

**Geliefert:** Deckungsgleich — `authStore` abonniert `addUserLoaded`/`addUserUnloaded`/`addSilentRenewError`, `apiInterceptors.ts` liest das Token asynchron und macht bei 401 einen `signinSilent()`-Versuch mit `_retry`-Guard vor dem endgültigen `expireSession()` (kein `signoutRedirect()` mehr im Fehlerfall). `keycloak/realm-export.json` setzt `accessTokenLifespan`/`ssoSessionIdleTimeout`/`ssoSessionMaxLifespan` explizit. ADR-0005 aktualisiert. Reproduktionsnachweis mit rotem (6 Testfehlschläge) und grünem Lauf im PR.

**Verifikation:** `frontend/src/stores/authStore.ts` und `frontend/src/services/apiInterceptors.ts` existieren im Worktree.

**Themen:** auth, frontend, keycloak, oidc, session-management
