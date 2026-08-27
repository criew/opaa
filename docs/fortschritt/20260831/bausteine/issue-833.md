# Issue #833 — fix(auth): lastLoginAt-Schreibzugriff pro Request drosseln
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #856 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 1 (Befund B2). `UserService.updateExistingUser` schreibt bei jedem authentifizierten Request unconditional `lastLoginAt` — ein UPDATE pro API-Call. Drosselung auf einen Schwellwert (z. B. 5 Minuten); E-Mail/DisplayName nur bei tatsächlicher Änderung speichern.

**Geliefert:** Wie gefordert. `lastLoginAt` wird nur noch aktualisiert, wenn der gespeicherte Wert mindestens 5 Minuten alt ist (`LAST_LOGIN_UPDATE_THRESHOLD`); E-Mail/DisplayName nur bei Differenz; kein `save()` mehr, wenn nichts zu schreiben ist. Für Testbarkeit wurde ein injizierter `Clock`-Bean ergänzt (`AuthConfiguration`).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserService.java` und `AuthConfiguration.java` im Worktree vorhanden. Reproduktionsnachweis in PR-Beschreibung dokumentiert (roter Test schlug mit `NeverWantedButInvoked` fehl, danach grün).

**Themen:** auth, backend, performance, bugfix
