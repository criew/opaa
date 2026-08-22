# Issue #441 — fix(library): createLibrary prüft Group#isDissolved() nicht vor dem Anlegen des Eigentümer-Grants
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #599 (2026-08-20)

**Laut Issue:** `KnowledgeLibraryService#createLibrary` legte den Eigentümer-Grant für gruppen-eigene Bibliotheken direkt über `grantRepository.save(...)` an, ohne zu prüfen, ob die Zielgruppe aufgelöst ist. `AssetGrantService#requireGrantableGroup` lehnt genau diesen Fall bei jeder anderen Grant-Vergabe bereits ab; `createLibrary` umging die Prüfung, weil es den Grant selbst schrieb. Gefordert war, `requireGrantableGroup` (oder eine gleichwertige Prüfung) vor dem Schreiben des Grants aufzurufen, mit einem Test für den Fall einer aufgelösten Gruppe als `ownerId`.

**Geliefert:** `requireGrantableGroup` wurde package-private gemacht und von `createLibrary` vor dem Schreiben des Gruppen-Grants wiederverwendet statt dupliziert — 400 mit der bestehenden deutschen Meldung. Neuer Test `createGroupOwnedLibraryRejectsADissolvedGroupAsOwner` in `KnowledgeLibraryServiceIntegrationTest`. Reproduktionsnachweis im PR belegt: Test schlägt ohne Fix fehl (`AssertionError: Expecting code to raise a throwable`), besteht mit Fix. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetGrantService.java` und `KnowledgeLibraryService.java` existieren im heutigen Stand des Worktrees unverändert an ihrem Ort; die im PR genannten Testdateien sind ebenfalls vorhanden. Kein tieferes Review vorgenommen.

**Themen:** library, grants, spaces, bugfix, backend
