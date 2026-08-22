# Issue #573 — chatStore: Modul-Maps der Einstellungs-Persistierung aufräumen und Navigation-Race beim bestätigten Zustand
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #618 (2026-08-20)

**Laut Issue:** Aus der zweiten Review-Runde zu PR #570 (#565), drei offene, nicht blockierende Punkte in `chatStore.ts`: (1) die Modul-Maps `settingsUpdateChains` und `confirmedSettingsByChatId` wachsen unbegrenzt über die Session; (2) `resetChatStore()` in Tests setzt diese Maps nicht zurück; (3) eine vorbestehende Navigation-Race, bei der ein spät erfolgreicher PATCH nach zwischenzeitlichem `loadChat` den bestätigten Zustand verdeckt.

**Geliefert:** Entspricht dem Issue. Der Ketteneintrag wird im `finally`-Handler von `applyScopeChange` entfernt, sofern er noch der Tail ist; `confirmedSettingsByChatId` bleibt beim Abarbeiten bewusst erhalten (Rollback-Korrektheit) und wird stattdessen bei Chat-Löschung über eine neue Funktion `dropChatSettingsCache(chatId)` bereinigt. Neue exportierte `clearSettingsPersistenceCache()` für Test-Resets. Der Erfolgs-Handler von `applyScopeChange` erhält denselben Sequenz-Guard wie der Fehler-Handler, was die Navigation-Race behebt. Rot/Grün-Nachweis für Punkt 3 dokumentiert.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `dropChatSettingsCache` und die Sequenz-Guards in Erfolgs- wie Fehlerpfad (Kommentare referenzieren #573 explizit).

**Themen:** chat, frontend, bugfix, race-condition
