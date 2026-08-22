# Issue #623 — test(chat): ChatServiceIntegrationTest hat dieselbe Stubbing-Race-Struktur wie #616
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #641 (2026-08-20)

**Laut Issue:** `ChatServiceIntegrationTest` teilt die Race-Struktur, die in `QueryIntegrationTest` (#616) einen Flake verursachte: mehrere Tests lösen über einen ersten Turn einen asynchronen Titel-Job aus, ohne dessen Ende abzuwarten, bevor spätere Tests den klassenweit geteilten `@MockitoBean chatModel` neu stubben. Der Sync-Executor-Fix aus #621 wurde als ungeeignet benannt, da die Klasse echte Async-Semantik per Latch prüft. Gefordert: laufende Titel-Jobs vor Re-Stubbing abwarten oder durchgängig `doReturn(...).when(...)` verwenden, ohne Latch-/Async-Tests zu schwächen.

**Geliefert:** Option (b) aus dem Issue umgesetzt — jedes `when(chatModel....).thenX(...)` durch `doX(...).when(chatModel)...` ersetzt (`doReturn`/`doAnswer`/`doThrow`, auch für `getOptions()` in `setUp()`), da die Klasse keinen `ArgumentCaptor` im Stubbing verwendet. `doAnswer` führt den übergebenen `Answer` weiterhin exakt einmal auf dem Job-Thread aus, die Latch-Synchronisation der beiden Async-Tests bleibt unverändert erhalten. Der im Issue genannte Reproduktionsnachweis konnte laut PR-Body nicht direkt mit künstlicher Verzögerung erbracht werden (lokale Gradle-Lock-Kontention durch parallele Agent-Sessions blockierte den Versuch); stattdessen wird die Ursachenanalyse aus #616/PR #621 als Beleg für denselben Fehlermechanismus referenziert — im Issue als Alternative vorgesehen. 10 Wiederholungsläufe der Klasse liefen grün.

**Verifikation:** `ChatServiceIntegrationTest.java` enthält 6 Treffer für `doReturn(`/`when(chatModel` — Umstellung im Code nachvollziehbar vorhanden.

**Themen:** backend, tests, ci, chat, flaky-tests
