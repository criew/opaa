# Issue #619 — chatStore: loadChat überschreibt Einstellungen ungeschützt gegen die laufende Settings-Kette
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, frontend, size:S
- PRs: #692 (2026-08-21)

**Laut Issue:** `loadChat` schrieb `scope`/bestätigten Einstellungszustand bedingungslos. Der Schutz aus PR #618 (zu #565/#573) deckte nur eine Ankunftsreihenfolge ab; in der umgekehrten Reihenfolge (GET vor PATCH-Commit abgesetzt, Antwort trifft aber nach der PATCH-Antwort ein) überschrieb `loadChat` den frisch bestätigten Serverzustand wieder mit dem veralteten Wert. Gefordert: `loadChat` darf `scope`/Zustand nicht anwenden, solange eine Settings-Kette für den Chat aussteht bzw. muss nach dem Settlen nachziehen, plus Reproduktionsnachweis für genau diese Reihenfolge.

**Geliefert:** Neuer, pro Chat geführter Zähler `settingsChangeSequenceByChatId`, der bei jeder `applyScopeChange`-Anfrage hochgezählt wird und anders als `settingsUpdateChains` auch nach dem Settlen der Kette bestehen bleibt. `loadChat` vergleicht den Zähler vor/nach seinem GET; hat er sich geändert, übernimmt `loadChat` `scope`/`referencedLibraryIds` nicht und überlässt sie dem eigenen Handler der Settings-Änderung. Andere Felder (Titel, Nachrichten) werden weiterhin angewendet. Entspricht der Anforderung.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält `settingsChangeSequenceByChatId` mit Set/Get/Clear/Delete an den beschriebenen Stellen (Zeilen 74, 85, 106, 266–283, 556) — Umsetzung im Code vorhanden.

**Themen:** frontend, chat, race-condition, spaces
