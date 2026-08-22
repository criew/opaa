# Issue #522 — chore(auth): Automatische persönliche Upload-Bibliothek beim Login entfernen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M
- PRs: #546 (2026-08-19)

**Laut Issue:** Die automatische Anlage einer persönlichen Upload-Bibliothek bei Erstanmeldung (`ensurePersonalLibrary`, `personal`-Flag) sollte ersatzlos entfallen. Die Anlage des persönlichen Space bleibt unberührt. `personal`-Flag/Spalte, Sonderlogik und zugehörige Zugriffs-/Anzeigepfade sollten aus Schema, Code, Spec und Frontend entfernt werden, bestehende automatisch angelegte Bibliotheken als gewöhnliche nutzereigene Bibliotheken erhalten bleiben.

**Geliefert:** PR #546 setzt das um: `UserService#ensurePersonalAssetsAfterCommit` vereinfacht auf reine Space-Provisionierung; `ensurePersonalLibrary`, `insertPersonalLibraryIfAbsent`, `insertOwnerGrantForPersonalLibraryIfAbsent` vollständig entfernt. `personal`-Spalte per Migration 033 entfernt (inkl. partiellem Unique-Index). Sonderpfade (Löschsperre, Sichtbarkeitssperre, Grant-Sperre) entfernt. OpenAPI-Spec und Frontend-Anzeige nachgezogen, Doku an vier Stellen aktualisiert. Migrationstest `Migration033DropKnowledgeLibrariesPersonalFlagTest` vorhanden.

**Verifikation:** `KnowledgeLibraryService.java` enthält `ensurePersonalLibrary`/`personal` nur noch als historische Javadoc-Referenz auf #522, keine aktiven Codepfade. Deckt sich mit dem PR-Anspruch.

**Themen:** auth, library, spaces, migration, cleanup, epic-458
