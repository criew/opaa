# Issue #565 — chatStore-Persistierung: Rollback ohne chatId-Guard und parallele PATCHes unserialisiert
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, frontend, size:S
- PRs: #570 (2026-08-20)

**Laut Issue:** Beim Review von PR #564 aufgefallen, vorbestehend seit #548: Der Fehler-Rollback der Chat-Einstellungs-Persistierung prüft nicht, ob inzwischen ein anderer Chat aktiv ist — ein spät fehlschlagender PATCH von Chat A kann den Zustand von Chat B überschreiben. Parallele PATCHes (schnelle Chip-Änderungen) werden nicht serialisiert, sodass die zuletzt eintreffende statt der zuletzt ausgelösten Aktion gewinnt.

**Geliefert:** `applyScopeChange` erhält ein monoton steigendes Token (`settingsUpdateSequence`) sowie die zum Änderungszeitpunkt aktive `chatId`; Einstellungs-PATCHes werden pro Chat als Promise-Kette (`settingsUpdateChains`) statt parallel verschickt. Rollback erfolgt auf den zuletzt serverbestätigten Zustand (`confirmedSettingsByChatId`), nicht auf den lokalen Zwischenstand. `sendMessage` wartet auf die Kette des aktiven Chats statt auf einen globalen Slot (zweite Review-Runde). Fünf Tests mit dokumentiertem Rot/Grün-Nachweis je Fix-Aspekt.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `settingsUpdateSequence` und `confirmedSettingsByChatId` weiterhin (weiterentwickelt durch #573, das offene Punkte aus der zweiten Review-Runde dieses PRs behebt — Modul-Maps-Aufräumung und eine vorbestehende Navigation-Race).

**Themen:** chat, frontend, bugfix, race-condition
